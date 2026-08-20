#!/usr/bin/env bash
# Runs the full emulator-based instrumented tier (issue #34) against whatever single device
# `adb` currently sees -- meant to run as the `script:` step inside a booted
# reactivecircus/android-emulator-runner job (.github/workflows/ci.yml) or against a locally
# booted AVD (scripts/run-instrumented-tests.sh). Never touches a physically connected device
# (the S25) on purpose -- see the serial-selection guard below.
#
# Two things happen here, not one plain `./gradlew connectedDebugAndroidTest`:
#   1. Every instrumented test EXCEPT InterruptionSpliceTest runs the normal way.
#   2. InterruptionSpliceTest runs separately, launched directly via `adb shell am instrument`,
#      because this script must inject two real GSM calls (`adb emu gsm call`/`gsm cancel`) at
#      the right moments -- `adb emu` talks to the emulator's host-side console port and cannot
#      be issued from on-device instrumentation, so Gradle's connectedAndroidTest task has no
#      hook for it. See InterruptionSpliceTest's class doc for the synchronization contract (one
#      logcat marker, then a bounded poll on real engine state -- no fixed sleep stands in for an
#      assertion on either side of this handshake).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

log()  { printf '[instrumented-tier] %s\n' "$*"; }
fail() { printf '[instrumented-tier] ERROR: %s\n' "$*" >&2; exit 1; }

APP_ID="cc.machado.audioblackbox"
TEST_APP_ID="${APP_ID}.test"
RUNNER="${TEST_APP_ID}/androidx.test.runner.AndroidJUnitRunner"
SPLICE_TEST_CLASS="cc.machado.audioblackbox.InterruptionSpliceTest"
MARKER_TAG="SpliceTest"
MARKER_READY="READY_FOR_CALLS"
FAKE_CALLER_NUMBER="5551234567"

command -v adb >/dev/null 2>&1 || fail "adb not found on PATH."

# --- Resolve exactly one emulator serial --------------------------------
# Deliberately only matches "emulator-*" serials: this script must never drive a physically
# attached device (the repo owner's S25 may be connected while this runs elsewhere) even if one
# happens to be listed alongside the AVD.
mapfile -t EMULATOR_SERIALS < <(adb devices | awk '$2=="device" && $1 ~ /^emulator-/ {print $1}')
if [[ "${#EMULATOR_SERIALS[@]}" -eq 0 ]]; then
  fail "No booted emulator found in 'adb devices' (only emulator-* serials are eligible; a physical device is never driven by this script)."
fi
if [[ "${#EMULATOR_SERIALS[@]}" -gt 1 ]]; then
  fail "Multiple emulator serials found (${EMULATOR_SERIALS[*]}); this script assumes exactly one."
fi
SERIAL="${EMULATOR_SERIALS[0]}"
ADB=(adb -s "$SERIAL")
log "Target emulator: $SERIAL"

# --- Build + install ------------------------------------------------------
log "Assembling debug + androidTest APKs..."
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon -q

APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" ]] || fail "$APP_APK not found after assemble."
[[ -f "$TEST_APK" ]] || fail "$TEST_APK not found after assemble."

log "Installing app + test APKs..."
"${ADB[@]}" install -r -t "$APP_APK" >/dev/null
"${ADB[@]}" install -r -t "$TEST_APK" >/dev/null

# --- Phase 1: everything except InterruptionSpliceTest --------------------
log "Running the non-interruption instrumented tests..."
set +e
"${ADB[@]}" shell am instrument -w -e notClass "$SPLICE_TEST_CLASS" "$RUNNER" \
  2>&1 | tee /tmp/instrumented-phase1.log
PHASE1_STATUS=$?
set -e
if [[ $PHASE1_STATUS -ne 0 ]] || ! grep -q "OK (" /tmp/instrumented-phase1.log; then
  fail "Non-interruption instrumented tests did not report OK -- see the log above."
fi
log "Phase 1 OK."

# --- Phase 2: InterruptionSpliceTest, with two real simulated calls -------
log "Launching InterruptionSpliceTest in the background..."
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am instrument -w -e class "$SPLICE_TEST_CLASS" "$RUNNER" \
  >/tmp/instrumented-splice.log 2>&1 &
SPLICE_PID=$!

wait_for_marker() {
  local deadline=$((SECONDS + 60))
  while [[ $SECONDS -lt $deadline ]]; do
    if "${ADB[@]}" logcat -d -s "${MARKER_TAG}:I" 2>/dev/null | grep -q "$MARKER_READY"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

log "Waiting for the test to confirm recording has actually started..."
if ! wait_for_marker; then
  kill "$SPLICE_PID" 2>/dev/null || true
  wait "$SPLICE_PID" 2>/dev/null || true
  cat /tmp/instrumented-splice.log >&2 || true
  fail "InterruptionSpliceTest never logged '$MARKER_READY' -- recording did not start (see log above)."
fi
log "Recording confirmed. Driving two simulated GSM calls..."

# Generous fixed margins: real telephony state changes (ringing -> answered/active -> ended)
# take a couple of seconds to propagate through the emulator's radio stack to
# AudioManager.AudioRecordingCallback. The test itself does not depend on this exact timing --
# it polls engine.gaps.value for a bounded 120s -- so these numbers only need to be "comfortably
# long enough", not exact.
sleep 3
log "Call 1: ringing..."
"${ADB[@]}" emu gsm call "$FAKE_CALLER_NUMBER"
sleep 5
log "Call 1: ending..."
"${ADB[@]}" emu gsm cancel "$FAKE_CALLER_NUMBER"
sleep 5

log "Call 2: ringing..."
"${ADB[@]}" emu gsm call "$FAKE_CALLER_NUMBER"
sleep 5
log "Call 2: ending..."
"${ADB[@]}" emu gsm cancel "$FAKE_CALLER_NUMBER"
sleep 5

log "Both calls issued. Waiting for the instrumentation run to finish (export + assertions)..."
set +e
wait "$SPLICE_PID"
SPLICE_STATUS=$?
set -e
cat /tmp/instrumented-splice.log
if [[ $SPLICE_STATUS -ne 0 ]] || ! grep -q "OK (" /tmp/instrumented-splice.log; then
  fail "InterruptionSpliceTest did not report OK -- see the log above."
fi
log "Phase 2 (InterruptionSpliceTest) OK."

log "Instrumented tier: all tests passed."
