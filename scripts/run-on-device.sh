#!/usr/bin/env bash
# Build the debug APK, install it on a connected Android device, and launch
# the main activity. Meant to run inside WSL with adb already able to see
# the device (see docs/development/running-on-device.md for one-time and
# per-session setup).
#
# Fails loudly and does nothing destructive if no device is attached.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log()  { printf '[run-on-device] %s\n' "$*"; }
fail() { printf '[run-on-device] ERROR: %s\n' "$*" >&2; exit 1; }

# --- Locate adb -------------------------------------------------------
ADB_BIN="${ADB_BIN:-}"
if [[ -z "$ADB_BIN" ]]; then
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
  elif [[ -f "$REPO_ROOT/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | tail -n1)"
    if [[ -n "$sdk_dir" && -x "$sdk_dir/platform-tools/adb" ]]; then
      ADB_BIN="$sdk_dir/platform-tools/adb"
    fi
  fi
fi
[[ -n "$ADB_BIN" && -x "$ADB_BIN" ]] || fail \
  "adb not found. Install the SDK per docs/development/running-on-device.md and either put platform-tools on \$PATH, set \$ADB_BIN, or set sdk.dir in local.properties."

log "Using adb: $ADB_BIN"

# --- Require exactly one authorized device -----------------------------
# adb devices -l output looks like:
#   List of devices attached
#   R58N60ABCDE            device usb:...
#   192.168.0.42:41287     device product:...
mapfile -t DEVICE_LINES < <("$ADB_BIN" devices -l | tail -n +2 | sed '/^$/d')

if [[ "${#DEVICE_LINES[@]}" -eq 0 ]]; then
  fail "No device attached. Reconnect via wireless debugging (adb connect <ip>:<port>) or plug in USB, then re-run. See docs/development/running-on-device.md."
fi

READY_DEVICES=()
for line in "${DEVICE_LINES[@]}"; do
  state="$(awk '{print $2}' <<<"$line")"
  serial="$(awk '{print $1}' <<<"$line")"
  case "$state" in
    device) READY_DEVICES+=("$serial") ;;
    unauthorized) fail "Device $serial is unauthorized. Check the phone screen for the 'Allow debugging?' / pairing dialog and accept it, then re-run." ;;
    offline) fail "Device $serial is offline. Try: adb disconnect $serial && adb connect <ip>:<port> (get the current port from Wireless debugging settings)." ;;
    *) fail "Device $serial is in unexpected state '$state'." ;;
  esac
done

if [[ "${#READY_DEVICES[@]}" -gt 1 ]]; then
  fail "Multiple ready devices found (${READY_DEVICES[*]}). Set ANDROID_SERIAL to pick one."
fi

TARGET="${ANDROID_SERIAL:-${READY_DEVICES[0]}}"
log "Target device: $TARGET"

# --- Build + install via Gradle ----------------------------------------
[[ -x "$REPO_ROOT/gradlew" ]] || fail "gradlew not found at repo root. Is the Android project skeleton (#8) present on this branch?"

export ANDROID_SERIAL="$TARGET"
log "Building and installing debug APK (./gradlew installDebug)..."
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" installDebug

# --- Determine applicationId to launch ----------------------------------
APP_BUILD_GRADLE=""
for candidate in app/build.gradle.kts app/build.gradle; do
  if [[ -f "$REPO_ROOT/$candidate" ]]; then
    APP_BUILD_GRADLE="$REPO_ROOT/$candidate"
    break
  fi
done
[[ -n "$APP_BUILD_GRADLE" ]] || fail "Could not find app/build.gradle(.kts) to determine applicationId."

APP_ID="$(grep -Eo 'applicationId[[:space:]]*=?[[:space:]]*"[^"]+"' "$APP_BUILD_GRADLE" | head -n1 | grep -Eo '"[^"]+"' | tr -d '"')"
[[ -n "$APP_ID" ]] || fail "Could not parse applicationId out of $APP_BUILD_GRADLE."

log "Launching $APP_ID on $TARGET..."
"$ADB_BIN" -s "$TARGET" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null

log "Done. $APP_ID is installed and launched on $TARGET."
