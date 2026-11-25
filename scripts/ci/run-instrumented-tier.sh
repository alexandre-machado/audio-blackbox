#!/usr/bin/env bash
# Runs the full emulator-based instrumented tier (issue #34) against whatever single device
# `adb` currently sees -- meant to run as the `script:` step inside a booted
# reactivecircus/android-emulator-runner job (.github/workflows/android.yml) or against a locally
# booted AVD (scripts/run-instrumented-tests.sh). Never touches a physically connected device
# (the S25) on purpose -- see the serial-selection guard below.
#
# Three phases run here instead of one plain `./gradlew connectedDebugAndroidTest`:
#   1. Normal instrumented tests, including Ogg prefix qualification, run first.
#   2. OpusProcessKillTest runs with an external kill/restart handshake.
#   3. InterruptionSpliceTest runs separately, launched directly via `adb shell am instrument`,
#      because this script must inject two real GSM calls (`adb emu gsm call`/`gsm cancel`) at
#      the right moments -- `adb emu` talks to the emulator's host-side console port and cannot
#      be issued from on-device instrumentation, so Gradle's connectedAndroidTest task has no
#      hook for it. See InterruptionSpliceTest's class doc for the synchronization contract (one
#      marker per observed event, with bounded waits -- no fixed sleep stands in for an
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
OPUS_KILL_TEST_CLASS="cc.machado.audioblackbox.export.OpusProcessKillTest"
# Where ScreenshotCaptureTest (issue #78) writes its PNGs, relative to the app's data dir, and
# where they land in the workspace for .github/workflows/android.yml to upload as an artifact. The
# device path is the app's *internal* files dir on purpose: `run-as` reads it on any API level
# without a storage permission, unlike /sdcard/Android/data.
SCREENSHOT_DEVICE_DIR="files/screenshots"
SCREENSHOT_OUT_DIR="build/screen-captures"
MARKER_TAG="SpliceTest"
FAKE_CALLER_NUMBER="5551234567"

command -v adb >/dev/null 2>&1 || fail "adb not found on PATH."

# Exact argv pairs, not substring/regex matches: a similarly named AVD or port is not ours.
is_job_qemu() {
  local proc="$1" i avd=false port=false
  local -a args=()
  [[ -r "$proc/cmdline" ]] || return 1
  mapfile -d '' -t args < "$proc/cmdline" 2>/dev/null || return 1
  [[ ${#args[@]} -gt 0 ]] || return 1
  [[ "${args[0]##*/}" == qemu-system-* ]] || return 1
  for ((i=1; i<${#args[@]}-1; i++)); do
    [[ "${args[i]}" == -avd && "${args[i+1]}" == "$AVD_NAME" ]] && avd=true
    [[ "${args[i]}" == -port && "${args[i+1]}" == "$EMULATOR_PORT" ]] && port=true
  done
  [[ "$avd" == true && "$port" == true ]]
}

wait_for_emulator_exit() {
  local deadline=$((SECONDS + 10)) devices
  while ((SECONDS < deadline)); do
    # Count offline serials too; a failed adb query is not proof of disappearance.
    if devices=$(timeout -k 1 2 adb devices 2>/dev/null); then
      if ! awk -v serial="$ANDROID_SERIAL" '$1 == serial { found=1 } END { exit !found }' <<< "$devices"; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

shutdown_ci_emulator() {
  local status=$? proc pid
  local -a matches=()
  trap - EXIT
  set +e
  # Only talk to the console if we proved this serial runs our AVD (#463 @sec SEC-1): another
  # distro could have grabbed the port between the pre-check and our emulator's launch.
  if [[ "${CI_EMULATOR_VERIFIED:-}" == true ]]; then
    timeout -k 2 15 adb -s "$ANDROID_SERIAL" emu kill
  else
    printf '::warning::%s was never verified as AVD %s; skipping emu kill.\n' "$ANDROID_SERIAL" "$AVD_NAME" >&2
  fi
  if ! wait_for_emulator_exit; then
    printf '::warning::%s did not disappear; attempting bounded, AVD-and-port-scoped qemu fallback.\n' "$ANDROID_SERIAL" >&2
    for proc in /proc/[0-9]*; do
      is_job_qemu "$proc" && matches+=("$proc")
    done
    if [[ ${#matches[@]} -eq 1 ]] && is_job_qemu "${matches[0]}"; then
      proc="${matches[0]}"
      pid="${proc##*/}"
      # SIGKILL avoids another unbounded graceful-shutdown wait after the console failed.
      kill -KILL "$pid" || printf '::warning::Could not kill CI qemu PID %s.\n' "$pid" >&2
      wait_for_emulator_exit || printf '::warning::%s still listed after qemu fallback.\n' "$ANDROID_SERIAL" >&2
    else
      printf '::warning::Found %s matching qemu processes (or identity changed); killing none.\n' "${#matches[@]}" >&2
    fi
  fi
  exit "$status"
}

# Register before any test/build failure can exit. Local callers retain their running AVD.
# The action supplies EMULATOR_PORT and ANDROID_SERIAL from its emulator-port input.
if [[ "${CI_EMULATOR_SHUTDOWN:-}" == true ]]; then
  [[ "${EMULATOR_PORT:-}" =~ ^55[5-8][0-9]$ ]] && (( EMULATOR_PORT % 2 == 0 && EMULATOR_PORT >= 5554 && EMULATOR_PORT <= 5584 )) &&
    [[ "${ANDROID_SERIAL:-}" == "emulator-$EMULATOR_PORT" && -n "${AVD_NAME:-}" ]] ||
    fail "CI cleanup requires EMULATOR_PORT (even, 5554-5584), ANDROID_SERIAL=emulator-\$EMULATOR_PORT and AVD_NAME."
  command -v timeout >/dev/null || fail "timeout is required for bounded CI shutdown."
  trap shutdown_ci_emulator EXIT
fi

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
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  # An explicit override always wins, even when the only listed emulator is someone else's.
  if ! printf '%s\n' "${EMULATOR_SERIALS[@]}" | grep -Fxq -- "$ANDROID_SERIAL"; then
    fail "ANDROID_SERIAL=$ANDROID_SERIAL is not among the booted emulators (${EMULATOR_SERIALS[*]})."
  fi
  SERIAL="$ANDROID_SERIAL"
elif [[ "${#EMULATOR_SERIALS[@]}" -gt 1 ]]; then
  if [[ -n "${AVD_NAME:-}" ]]; then
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

# In CI, refuse to drive an emulator we cannot prove is ours (#463 @sec SEC-1). The console
# must answer with this job's AVD name; a foreign emulator on the port fails closed here.
if [[ "${CI_EMULATOR_SHUTDOWN:-}" == true ]]; then
  running_avd="$(timeout -k 2 10 "${ADB[@]}" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
  if [[ "$running_avd" != "$AVD_NAME" ]]; then
    fail "$SERIAL reports AVD '${running_avd:-<no answer>}', expected '$AVD_NAME'; refusing to test or kill a foreign emulator."
  fi
  CI_EMULATOR_VERIFIED=true
  log "Verified $SERIAL runs AVD '$AVD_NAME'."
fi

# --- Build + install ------------------------------------------------------
log "Assembling debug + androidTest APKs..."
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon -q

APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" ]] || fail "$APP_APK not found after assemble."
[[ -f "$TEST_APK" ]] || fail "$TEST_APK not found after assemble."

log "Installing app + test APKs..."
# -d (allow version downgrade) is required on a persistent runner, not optional. versionCode is
# `100 + GITHUB_RUN_NUMBER` (app/build.gradle.kts), and the AVD is not wiped between runs here, so
# whatever the highest-numbered run installed stays on the device. Any later job with a lower run
# number -- a re-run of an older run, or a branch built before a newer one landed -- then fails
# with INSTALL_FAILED_VERSION_DOWNGRADE before a single test executes. On the ephemeral hosted
# runners this script was written for, the emulator was fresh every time and this could not happen.
"${ADB[@]}" install -r -d -t "$APP_APK" >/dev/null
"${ADB[@]}" install -r -d -t "$TEST_APK" >/dev/null

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

# --- Phase 1: tests that do not require an external handshake -------------
# Cleared so the headroom-benchmark dump below (issue #22) only picks up this run's lines, not
# leftovers from some earlier install/boot activity that happened to log the same tag.
"${ADB[@]}" logcat -c
log "Running the non-interruption instrumented tests..."
set +e
"${ADB[@]}" shell am instrument -w -e notClass "$SPLICE_TEST_CLASS,$OPUS_KILL_TEST_CLASS" "$RUNNER" \
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

# #433: Phase 1 includes the Ogg truncation qualification. A failure above is the owner's
# stop gate under AB-2411-44: RAW one-page reader limitations are logged, but repair must pass.
# The first instrumentation process is deliberately killed with its production Live writer open.
log "Starting the Opus live process-kill qualification..."
command -v timeout >/dev/null || fail "timeout is required for the process-kill qualification."
"${ADB[@]}" logcat -c
timeout -k 5 120 "${ADB[@]}" shell am instrument -w -e class "$OPUS_KILL_TEST_CLASS" -e opusKillPhase start "$RUNNER" \
  > /tmp/instrumented-opus-kill-start.log 2>&1 &
OPUS_START_PID=$!
opus_ready="$(timeout -k 2 90 "${ADB[@]}" logcat -m 1 -e 'OPUS_KILL_READY pid=[0-9]+' -s OpusKillTest:I | grep -oE 'OPUS_KILL_READY pid=[0-9]+' || true)"
if [[ -z "$opus_ready" ]]; then
  timeout -k 2 15 "${ADB[@]}" shell am force-stop "$APP_ID"
  wait "$OPUS_START_PID" || true
  fail "Opus live writer never reported READY; see /tmp/instrumented-opus-kill-start.log."
fi
opus_app_pid="${opus_ready##*=}"
opus_actual_pid="$(timeout -k 2 5 "${ADB[@]}" shell pidof "$APP_ID" | tr -d '\r' || true)"
[[ "$opus_app_pid" == "$opus_actual_pid" ]] || fail "The READY app PID is no longer alive; no process-kill result can be claimed."
timeout -k 2 15 "${ADB[@]}" shell am force-stop "$APP_ID"
wait "$OPUS_START_PID" || true # Process death is expected; verifier success is the oracle.
[[ -z "$(timeout -k 2 5 "${ADB[@]}" shell pidof "$APP_ID" 2>/dev/null || true)" ]] || fail "Opus app process survived force-stop."
# Snapshot after death and before Application.onCreate can repair the MediaStore row.
# API 30 scoped storage denies direct access to this pending MediaStore row even
# through run-as. Read as root without restarting adbd; never skip raw evidence.
opus_root_uid="$(timeout -k 2 15 "${ADB[@]}" exec-out su 0 id -u)" \
  || fail "Raw Opus snapshot requires working su 0 on the CI emulator (unavailable or timed out)."
[[ "$opus_root_uid" == 0 ]] || fail "Raw Opus snapshot requires su 0 to return root UID 0."
opus_marker="$(timeout -k 2 15 "${ADB[@]}" exec-out run-as "$APP_ID" cat files/opus-kill-row.txt)" \
  || fail "Could not read the killed Opus row marker."
mapfile -t opus_marker_lines <<< "$opus_marker"
opus_path="${opus_marker_lines[2]:-}"
# adb joins remote arguments into shell text; accept only the fixture's known path.
[[ "$opus_path" =~ ^/storage/emulated/0/Music/Blackbox/blackbox_[0-9]+_kill_forward\.ogg$ ]] \
  || fail "Killed Opus row marker contains an unexpected file path."
opus_device_size="$(timeout -k 2 15 "${ADB[@]}" exec-out su 0 stat -c %s "$opus_path")" \
  || fail "Could not stat the raw killed Opus file as root."
[[ "$opus_device_size" =~ ^[1-9][0-9]*$ ]] || fail "Raw killed Opus file has an empty or invalid size."
opus_host_snapshot="build/opus-killed-raw.ogg"
timeout -k 2 15 "${ADB[@]}" exec-out su 0 cat "$opus_path" > "$opus_host_snapshot" \
  || fail "Could not read the raw killed Opus file as root."
opus_host_size="$(timeout -k 2 15 stat -c %s "$opus_host_snapshot")" \
  || fail "Could not stat the host raw Opus snapshot."
[[ -s "$opus_host_snapshot" && "$opus_host_size" == "$opus_device_size" ]] \
  || fail "Raw Opus snapshot size mismatch: device=$opus_device_size host=$opus_host_size."
# Create through run-as to retain app ownership and SELinux context. The verifier
# reads this exact internal path before inspecting the separately repaired row.
# `shell -T` (shell protocol, no PTY) forwards stdin byte-for-byte; exec-out does not
# reliably forward stdin, and a PTY would mangle binary data.
timeout -k 2 15 "${ADB[@]}" shell -T run-as "$APP_ID" sh -c \
  "'cat > files/opus-killed-raw.ogg'" < "$opus_host_snapshot" \
  || fail "Could not install the raw Opus snapshot in the app files directory."
opus_app_size="$(timeout -k 2 15 "${ADB[@]}" exec-out run-as "$APP_ID" stat -c %s files/opus-killed-raw.ogg)" \
  || fail "Could not stat the app-readable raw Opus snapshot."
[[ "$opus_app_size" == "$opus_device_size" ]] \
  || fail "App raw Opus snapshot size mismatch: device=$opus_device_size app=$opus_app_size."
timeout -k 5 180 "${ADB[@]}" shell am instrument -w -e class "$OPUS_KILL_TEST_CLASS" -e opusKillPhase verify "$RUNNER" \
  2>&1 | tee /tmp/instrumented-opus-kill-verify.log
grep -qE 'OK \([1-9][0-9]* test' /tmp/instrumented-opus-kill-verify.log || fail "Opus killed-file/startup-recovery verifier did not report OK."
log "Opus process-kill and startup-recovery qualification OK."

# --- Phase 2: InterruptionSpliceTest, with two real simulated calls -------
# Budget contract with InterruptionSpliceTest.EVENT_TIMEOUT_MILLIS = 10s:
# Segment                    Test waits   Host limit (seconds)
# launch -> READY_1          3 * 10 = 30   50
# PCM_1 -> READY_2           0              20
# READY_n -> RINGING_n       1 * 10 = 10   30
# RINGING_n -> PAUSED_n      1 * 10 = 10   30
# PAUSED_n -> PCM_n          4 * 10 = 40   60
# PCM_2 -> completion        3 * 10 = 30   (outer limit)
# Whole test (setup + 2 initial + 12 call + 2 export + teardown): 180s.
# Marker limits include 10s teardown after a failed assertion + 10s reporting margin.
# Outer 300s allows all 180s of test waits + 60s for six host commands + 60s
# for instrumentation startup, framework work, metadata reads and result reporting.
SPLICE_PHASE_SECONDS=300
log "Launching InterruptionSpliceTest in the background..."
"${ADB[@]}" logcat -c
timeout -k 5 "$SPLICE_PHASE_SECONDS" "${ADB[@]}" shell am instrument -w -e class "$SPLICE_TEST_CLASS" "$RUNNER" \
  >/tmp/instrumented-splice.log 2>&1 &
SPLICE_PID=$!

# ERR cleanup leaves the existing EXIT emulator-shutdown trap intact. Inherit into
# functions so marker-reader failures and all emu/keyevent failures take this path.
splice_failed() {
  local status=$?
  trap - ERR
  kill "$SPLICE_PID" 2>/dev/null || true
  wait "$SPLICE_PID" 2>/dev/null || true
  cat /tmp/instrumented-splice.log >&2 || true
  printf '[instrumented-tier] ERROR: splice phase failed (status %s).\n' "$status" >&2
  exit "$status"
}
set -E
trap splice_failed ERR

wait_for_marker() {
  local marker="$1"
  local budget="$2"
  # logcat includes retained messages, so a fast callback before this reader starts is safe.
  log "Waiting up to ${budget}s for $marker..."
  timeout -k 2 "$budget" "${ADB[@]}" logcat -m 1 -e "^${marker}$" -s "${MARKER_TAG}:I"
}

for call in 1 2; do
  if [[ "$call" == 1 ]]; then
    wait_for_marker "READY_$call" 50
  else
    wait_for_marker "READY_$call" 20
  fi
  log "Call $call: ringing..."
  timeout -k 2 10 "${ADB[@]}" emu gsm call "$FAKE_CALLER_NUMBER"
  wait_for_marker "RINGING_$call" 30
  # A real framework ringing callback acknowledges that KEYCODE_CALL can answer it.
  timeout -k 2 10 "${ADB[@]}" shell input keyevent KEYCODE_CALL
  wait_for_marker "PAUSED_$call" 30
  timeout -k 2 10 "${ADB[@]}" emu gsm cancel "$FAKE_CALLER_NUMBER"
  wait_for_marker "PCM_$call" 60
done

log "Both calls issued. Waiting for the instrumentation run to finish (export + assertions)..."
wait "$SPLICE_PID"
trap - ERR
cat /tmp/instrumented-splice.log
if ! grep -q "OK (" /tmp/instrumented-splice.log; then
  fail "InterruptionSpliceTest did not report OK -- see the log above."
fi
log "Phase 2 (InterruptionSpliceTest) OK."

log "Instrumented tier: all tests passed."
