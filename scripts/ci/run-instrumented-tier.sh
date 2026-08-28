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

# The screen captures (issue #78) currently on the device, one filename per line, empty if there
# are none. Two things about this AVD's toybox `ls` are load-bearing here, both learned the hard
# way on this branch rather than assumed:
#   - for a missing directory it prints "ls: <dir>: No such file or directory" on *stdout* and
#     still exits 0, so neither the exit status nor the raw output distinguishes "empty" from
#     "gone"; only the shape of the names does.
#   - it lays names out in padded columns rather than one per line, so nothing may be matched
#     line-anchored without normalizing the whitespace first.
# Hence: split on any whitespace, then keep only what looks like one of the PNGs
# ScreenshotCaptureTest writes. Every token of the error message fails that pattern.
list_device_captures() {
  "${ADB[@]}" exec-out run-as "$APP_ID" ls "$SCREENSHOT_DEVICE_DIR" 2>/dev/null |
    tr -s ' \t\r' '\n' |
    grep -E '^[A-Za-z0-9._-]+\.png$' || true
}

APP_ID="cc.machado.audioblackbox.staging"
TEST_APP_ID="${APP_ID}.test"
RUNNER="${TEST_APP_ID}/androidx.test.runner.AndroidJUnitRunner"
SPLICE_TEST_CLASS="cc.machado.audioblackbox.InterruptionSpliceTest"
# Where ScreenshotCaptureTest (issue #78) writes its PNGs, relative to the app's data dir, and
# where they land in the workspace for .github/workflows/ci.yml to upload as an artifact. The
# device path is the app's *internal* files dir on purpose: `run-as` reads it on any API level
# without a storage permission, unlike /sdcard/Android/data.
SCREENSHOT_DEVICE_DIR="files/screenshots"
SCREENSHOT_OUT_DIR="build/screen-captures"
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
# More than one emulator is normal on a self-hosted runner: the machine belongs to a human who
# may have an AVD of their own open, and CI does not get to assume the box is idle. Rather than
# refusing to run (which is what happened when a manually started emulator-5556 collided with
# CI's emulator-5554 and failed the tier before a single test ran), pick *our own* AVD out of the
# list. Only fall back to "there must be exactly one" when there is nothing to match against.
if [[ "${#EMULATOR_SERIALS[@]}" -gt 1 ]]; then
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    # An explicit override always wins, and must actually be present.
    if ! printf '%s\n' "${EMULATOR_SERIALS[@]}" | grep -qx "$ANDROID_SERIAL"; then
      fail "ANDROID_SERIAL=$ANDROID_SERIAL is not among the booted emulators (${EMULATOR_SERIALS[*]})."
    fi
    SERIAL="$ANDROID_SERIAL"
  elif [[ -n "${AVD_NAME:-}" ]]; then
    # Ask each emulator which AVD it is running and keep the one this workflow created.
    SERIAL=""
    for candidate in "${EMULATOR_SERIALS[@]}"; do
      candidate_avd="$(adb -s "$candidate" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
      if [[ "$candidate_avd" == "$AVD_NAME" ]]; then
        SERIAL="$candidate"
        break
      fi
    done
    if [[ -z "$SERIAL" ]]; then
      fail "Multiple emulators booted (${EMULATOR_SERIALS[*]}) and none is running AVD '$AVD_NAME'."
    fi
    log "Multiple emulators booted (${EMULATOR_SERIALS[*]}); selected $SERIAL running AVD '$AVD_NAME'."
  else
    fail "Multiple emulator serials found (${EMULATOR_SERIALS[*]}) and neither ANDROID_SERIAL nor AVD_NAME is set to disambiguate."
  fi
else
  SERIAL="${EMULATOR_SERIALS[0]}"
fi
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

# `install -r` keeps the app's data dir, so captures from an earlier run on the same emulator
# would otherwise be pulled and uploaded as if this run had produced them -- and would make the
# "no captures were written" check below pass on the previous run's images. Ephemeral on CI, but
# scripts/run-instrumented-tests.sh drives a persistent local AVD where it matters, so the removal
# is verified rather than assumed: `|| true` cannot tell "removed" from "run-as refused".
"${ADB[@]}" shell run-as "$APP_ID" rm -rf "$SCREENSHOT_DEVICE_DIR" >/dev/null 2>&1 || true
STALE_CAPTURES="$(list_device_captures)"
if [[ -n "$STALE_CAPTURES" ]]; then
  fail "Stale screen captures survive at the app's $SCREENSHOT_DEVICE_DIR ($(echo "$STALE_CAPTURES" | tr '\n' ' ')) -- refusing to run, since the pull would upload a previous run's PNGs as this run's."
fi

# --- Phase 1: everything except InterruptionSpliceTest --------------------
# Cleared so the headroom-benchmark dump below (issue #22) only picks up this run's lines, not
# leftovers from some earlier install/boot activity that happened to log the same tag.
"${ADB[@]}" logcat -c
log "Running the non-interruption instrumented tests..."
set +e
"${ADB[@]}" shell am instrument -w -e notClass "$SPLICE_TEST_CLASS" "$RUNNER" \
  2>&1 | tee /tmp/instrumented-phase1.log
PHASE1_STATUS=$?
set -e

# --- Screen captures (issue #78) ------------------------------------------
# Pulled *before* phase 1's pass/fail gate below, deliberately: when a layout assertion fails, the
# picture of the screen it failed on is the single most useful thing a reviewer can look at, and it
# only exists on an emulator that is about to be thrown away.
log "Pulling screen captures written by ScreenshotCaptureTest (issue #78)..."
rm -rf "$SCREENSHOT_OUT_DIR"
mkdir -p "$SCREENSHOT_OUT_DIR"
CAPTURES="$(list_device_captures)"
if [[ -z "$CAPTURES" ]]; then
  # Which case this is has to be decided the same way the phase-1 gate below decides it: `am
  # instrument -w` exits 0 even when tests fail, which is why nothing in this script trusts
  # PHASE1_STATUS alone. Branching on the exit code here used to report "even though the tests
  # passed" for an ordinary test failure -- and `fail` before the gate, so the actual failure was
  # never printed.
  if grep -q "OK (" /tmp/instrumented-phase1.log; then
    # The tests passed, and ScreenshotCaptureTest asserts it wrote non-empty PNGs -- so an empty
    # pull means this transfer broke. Treating that as success would ship a green run with no
    # screenshots (same reasoning as the HeadroomBenchmark capture above).
    fail "No screen captures found under the app's $SCREENSHOT_DEVICE_DIR even though the tests passed -- the capture/pull path is broken."
  fi
  log "WARNING: no screen captures to pull -- the instrumented run failed before writing them; see the test failure below."
else
  # Quoted, line-at-a-time: a filename with a space would otherwise be split into two bogus pulls.
  while IFS= read -r capture; do
    [[ -n "$capture" ]] || continue
    "${ADB[@]}" exec-out run-as "$APP_ID" cat "$SCREENSHOT_DEVICE_DIR/$capture" > "$SCREENSHOT_OUT_DIR/$capture"
    [[ -s "$SCREENSHOT_OUT_DIR/$capture" ]] || fail "Pulled an empty file for screen capture '$capture'."
    # Non-empty is not the same as decodable: four corrupt files riding along on a green run is
    # exactly the failure a size check cannot see, so check the PNG signature (89 50 4e 47).
    MAGIC="$(od -An -tx1 -N4 < "$SCREENSHOT_OUT_DIR/$capture" | tr -d ' \n')"
    [[ "$MAGIC" == "89504e47" ]] || fail "Screen capture '$capture' is not a PNG (first four bytes: $MAGIC) -- the pull corrupted it."
    log "  $SCREENSHOT_OUT_DIR/$capture ($(wc -c < "$SCREENSHOT_OUT_DIR/$capture") bytes)"
  done <<< "$CAPTURES"
fi

if [[ $PHASE1_STATUS -ne 0 ]] || ! grep -q "OK (" /tmp/instrumented-phase1.log; then
  fail "Non-interruption instrumented tests did not report OK -- see the log above."
fi
log "Phase 1 OK."

# --- AudioRecord headroom numbers (issue #22) ------------------------------
# AudioRecordHeadroomInstrumentedTest logs its results under this tag instead of asserting on
# them (see that test's class doc) -- surface them here so they land in the CI job log where a
# human reads them, rather than staying buried in the emulator's logcat buffer.
#
# `logcat -d` exits 0 regardless of whether it matched anything (`@rev`'s finding on PR #69): a
# silently-dropped tag -- app process died before logging, buffer rotated, filter typo -- would
# otherwise pass a green CI run with the headroom numbers quietly missing. Capture the output and
# fail loudly if it is empty instead of trusting the exit code.
log "Dumping AudioRecord headroom benchmark results (issue #22)..."
HEADROOM_OUTPUT="$("${ADB[@]}" logcat -d -s HeadroomBenchmark:I)"
if [[ -z "$HEADROOM_OUTPUT" ]]; then
  fail "HeadroomBenchmark logcat tag produced no output -- AudioRecordHeadroomInstrumentedTest's results were lost (buffer rotated, process died, or the tag/filter drifted). Not treating an empty capture as success."
fi
printf '%s\n' "$HEADROOM_OUTPUT"

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

# `adb emu gsm call` alone only rings the simulated call -- it never becomes ACTIVE, and the
# framework only silences other apps' microphone clients once a call actually goes off-hook
# (confirmed empirically against a booted AVD: `dumpsys audio`'s RecordActivityMonitor line for
# our session stays `silenced:false` through ringing, flips to `silenced:true` only after the
# call is answered, and back to `false` on hangup). The emulator's console has no "answer an
# inbound call" subcommand (`adb emu gsm accept` is documented as outbound-call-only), but the
# stock google_apis image does ship a real Telecom/Dialer stack, and `KEYCODE_CALL` answers a
# ringing call through it the same way a hardware call button would on a real phone.
#
# Generous fixed margins: real telephony state changes (ringing -> answered/active -> ended)
# take a couple of seconds to propagate through the emulator's radio stack to
# AudioManager.AudioRecordingCallback. The test itself does not depend on this exact timing --
# it polls engine.gaps.value for a bounded 120s -- so these numbers only need to be "comfortably
# long enough", not exact.
sleep 3
log "Call 1: ringing..."
"${ADB[@]}" emu gsm call "$FAKE_CALLER_NUMBER"
sleep 2
log "Call 1: answering..."
"${ADB[@]}" shell input keyevent KEYCODE_CALL
sleep 4
log "Call 1: ending..."
"${ADB[@]}" emu gsm cancel "$FAKE_CALLER_NUMBER"
sleep 5

log "Call 2: ringing..."
"${ADB[@]}" emu gsm call "$FAKE_CALLER_NUMBER"
sleep 2
log "Call 2: answering..."
"${ADB[@]}" shell input keyevent KEYCODE_CALL
sleep 4
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
