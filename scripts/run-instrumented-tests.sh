#!/usr/bin/env bash
# Single documented command to run the emulator-based instrumented tier (issue #34) locally:
# creates/boots the AVD defined in scripts/ci/avd.env, runs the full instrumented suite against
# it via scripts/ci/run-instrumented-tier.sh, then shuts the emulator down. Never touches a
# physically connected device -- see run-instrumented-tier.sh's serial guard.
#
#   ./scripts/run-instrumented-tests.sh
#
# Requires:
#   - ANDROID_SDK_ROOT set (or sdk.dir in local.properties), with cmdline-tools installed (for
#     sdkmanager/avdmanager) -- see docs/development/running-on-device.md's one-time setup.
#   - Hardware acceleration: /dev/kvm present AND readable/writable by the current user (needs
#     `sudo usermod -aG kvm $USER` plus a re-login -- a one-time step for whoever runs this that
#     this script deliberately does not attempt itself; it fails loudly instead of trying sudo).
#
# On a machine without the kvm-group fix applied, this script fails at the "boot" step rather
# than silently running an unusably slow software-emulated instance -- CI (.github/workflows/ci.yml,
# job "instrumented-tests") is the verification loop until that fix lands locally.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log()  { printf '[run-instrumented-tests] %s\n' "$*"; }
fail() { printf '[run-instrumented-tests] ERROR: %s\n' "$*" >&2; exit 1; }

# shellcheck source=scripts/ci/avd.env
source "$REPO_ROOT/scripts/ci/avd.env"

# --- Resolve SDK root ------------------------------------------------------
if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ -f "$REPO_ROOT/local.properties" ]]; then
    ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | tail -n1)"
  fi
fi
[[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]] || fail \
  "ANDROID_SDK_ROOT not set/found (checked \$ANDROID_SDK_ROOT and local.properties's sdk.dir). See docs/development/running-on-device.md."
export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

command -v sdkmanager >/dev/null 2>&1 || fail "sdkmanager not on PATH under \$ANDROID_SDK_ROOT/cmdline-tools/latest/bin."
command -v avdmanager >/dev/null 2>&1 || fail "avdmanager not on PATH under \$ANDROID_SDK_ROOT/cmdline-tools/latest/bin."
command -v emulator   >/dev/null 2>&1 || fail "emulator binary not found -- install it: sdkmanager 'emulator'."

# --- KVM check: fail loudly, never sudo ------------------------------------
if [[ -e /dev/kvm ]]; then
  if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    fail "/dev/kvm exists but is not readable/writable by $(id -un). This needs 'sudo usermod -aG kvm \$USER' plus a re-login -- that is not something this script will do for you. Until then, use CI (job 'instrumented-tests' in .github/workflows/ci.yml) as the verification loop."
  fi
else
  fail "/dev/kvm not found -- hardware-accelerated emulation is unavailable on this machine. Use CI as the verification loop."
fi

SYSTEM_IMAGE="system-images;android-${API_LEVEL};${TARGET};${ARCH}"

log "Ensuring system image $SYSTEM_IMAGE is installed..."
# `|| true` guards a `set -o pipefail` trap, not a real failure: when the image is already
# installed, sdkmanager exits almost immediately and closes stdin, and `yes` gets SIGPIPE (exit
# 141) before it's done writing -- under pipefail that 141 propagates as this pipeline's exit
# status even though sdkmanager itself (the command whose outcome actually matters here) succeeded.
# Discovered running this script locally for issue #32/#33 (KVM-accelerated, image pre-installed
# from a prior run) -- sdkmanager's own success/failure still surfaces normally to stderr/stdout.
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "$SYSTEM_IMAGE" >/dev/null || true

if ! avdmanager list avd | grep -q "Name: ${AVD_NAME}$"; then
  log "Creating AVD $AVD_NAME ($SYSTEM_IMAGE, device profile $PROFILE)..."
  echo "no" | avdmanager create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "$PROFILE" \
    --force
else
  log "Reusing existing AVD $AVD_NAME."
fi

log "Booting $AVD_NAME headless..."
emulator -avd "$AVD_NAME" \
  -no-window -no-boot-anim -gpu swiftshader_indirect -camera-back none \
  -no-snapshot-save &
EMULATOR_PID=$!

cleanup() {
  log "Shutting down emulator (pid $EMULATOR_PID)..."
  adb -s "emulator-5554" emu kill >/dev/null 2>&1 || kill "$EMULATOR_PID" 2>/dev/null || true
}
trap cleanup EXIT

log "Waiting for device..."
# Targeted at the fixed "emulator-5554" serial (matching cleanup()'s own assumption above), not
# plain `adb` -- if the machine running this also has a physically paired device connected (e.g.
# wireless debugging to a phone kept connected for unrelated manual testing, same concern
# run-instrumented-tier.sh's own serial guard exists for), plain `adb shell`/`adb wait-for-device`
# becomes ambiguous across two devices and either errors or silently targets the wrong one. This
# script must never drive that physical device either way.
adb -s emulator-5554 wait-for-device
BOOT_DEADLINE=$((SECONDS + 180))
until [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  [[ $SECONDS -lt $BOOT_DEADLINE ]] || fail "Emulator did not report sys.boot_completed=1 within 180s."
  sleep 2
done
log "Boot complete."

"$REPO_ROOT/scripts/ci/run-instrumented-tier.sh"
