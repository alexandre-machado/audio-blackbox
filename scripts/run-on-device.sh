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

# --- Locate a JDK for gradlew -------------------------------------------
# gradlew needs a JVM on PATH or JAVA_HOME set; neither is guaranteed in a
# fresh shell even when the SDK/JDK were installed per the docs.
if [[ -z "${JAVA_HOME:-}" ]] && ! command -v java >/dev/null 2>&1; then
  fail "No Java runtime found (JAVA_HOME unset and 'java' not on \$PATH). Install JDK 17 per docs/development/running-on-device.md and export JAVA_HOME, or add it to \$PATH."
fi

# --- Require exactly one authorized device -----------------------------
# adb devices -l output looks like:
#   List of devices attached
#   R58N60ABCDE            device usb:...
#   192.168.0.42:41287     device product:foo model:bar device:baz transport_id:1
#
# A single physical device using wireless debugging can legitimately be
# listed TWICE: once by its live ip:port transport and once by its mDNS
# service name (adb-<serial>-xxxx._adb-tls-connect._tcp). Both entries
# report identical product/model/device fields for the same phone, just
# different transport_ids. We must collapse that case to one target
# without ever silently guessing between two genuinely different phones.
mapfile -t DEVICE_LINES < <("$ADB_BIN" devices -l | tail -n +2 | sed '/^$/d')

if [[ "${#DEVICE_LINES[@]}" -eq 0 ]]; then
  fail "No device attached. Reconnect via wireless debugging (adb connect <ip>:<port>) or plug in USB, then re-run. See docs/development/running-on-device.md."
fi

READY_SERIALS=()
READY_FINGERPRINTS=()
for line in "${DEVICE_LINES[@]}"; do
  serial="$(awk '{print $1}' <<<"$line")"
  state="$(awk '{print $2}' <<<"$line")"
  case "$state" in
    device)
      # Fingerprint = product:/model:/device: fields (everything after
      # state, minus transport_id which differs per transport of the same
      # phone). Two lines with the same fingerprint are the same physical
      # device seen over two transports.
      fingerprint="$(sed -E 's/^[^ ]+[[:space:]]+[^ ]+[[:space:]]*//; s/transport_id:[0-9]+//' <<<"$line" | tr -s '[:space:]')"
      READY_SERIALS+=("$serial")
      READY_FINGERPRINTS+=("$fingerprint")
      ;;
    unauthorized) fail "Device $serial is unauthorized. Check the phone screen for the 'Allow debugging?' / pairing dialog and accept it, then re-run." ;;
    offline) fail "Device $serial is offline. Try: adb disconnect $serial && adb connect <ip>:<port> (get the current port from Wireless debugging settings)." ;;
    *) fail "Device $serial is in unexpected state '$state'." ;;
  esac
done

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  TARGET="$ANDROID_SERIAL"
  found=0
  for s in "${READY_SERIALS[@]}"; do
    [[ "$s" == "$TARGET" ]] && found=1
  done
  [[ "$found" -eq 1 ]] || fail "ANDROID_SERIAL=$TARGET is not among the ready devices (${READY_SERIALS[*]})."
elif [[ "${#READY_SERIALS[@]}" -eq 1 ]]; then
  TARGET="${READY_SERIALS[0]}"
else
  # More than one ready serial. Check whether they are all the same
  # physical device (identical fingerprint) or genuinely different phones.
  SEEN_FINGERPRINTS=()
  distinct_fingerprints=0
  for fp in "${READY_FINGERPRINTS[@]}"; do
    is_new=1
    for seen in "${SEEN_FINGERPRINTS[@]:-}"; do
      [[ "$fp" == "$seen" ]] && is_new=0 && break
    done
    if [[ "$is_new" -eq 1 ]]; then
      SEEN_FINGERPRINTS+=("$fp")
      distinct_fingerprints=$((distinct_fingerprints + 1))
    fi
  done

  if [[ "$distinct_fingerprints" -gt 1 ]]; then
    fail "Multiple distinct devices found (${READY_SERIALS[*]}). Set ANDROID_SERIAL to pick one."
  fi

  # Same physical device listed under multiple transports (e.g. both a
  # live ip:port connection and its mDNS service name). Prefer the ip:port
  # form since it is what adb connect/install accept directly and it is
  # stable for the session; otherwise fall back to the first entry.
  TARGET=""
  for s in "${READY_SERIALS[@]}"; do
    if [[ "$s" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]+$ ]]; then
      TARGET="$s"
      break
    fi
  done
  [[ -n "$TARGET" ]] || TARGET="${READY_SERIALS[0]}"
  log "Note: device listed under multiple transports (${READY_SERIALS[*]}); using $TARGET."
fi

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

# adb shell joins argv and hands it to the REMOTE shell, so local array
# quoting does not stop shell metacharacters from being interpreted on the
# device. Validate against the Android package-name charset (letters,
# digits, underscore, dot; segments separated by dots) before it ever
# reaches "adb shell".
if [[ ! "$APP_ID" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
  fail "applicationId '$APP_ID' parsed from $APP_BUILD_GRADLE does not look like a valid Android package name; refusing to pass it to adb shell."
fi

log "Launching $APP_ID on $TARGET..."
"$ADB_BIN" -s "$TARGET" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null

log "Done. $APP_ID is installed and launched on $TARGET."
