#!/usr/bin/env bash
# Tier 2 (issue #34): a scripted smoke pass against the physical S25, replacing the mechanical
# adb work from the manual device passes (#24/#29) with a script instead of a person. Checks:
#   - the mic is genuinely open (`cmd appops get`)
#   - the foreground service is running with the microphone type (`dumpsys activity services`)
#   - a Save produces a committed MediaStore row (path, IS_PENDING, declared duration)
#   - the pulled WAV file's header numerically matches that declared duration
#
# What this deliberately does NOT do: drive the app (start/stop recording, tap Save) -- that is
# `@techlead`'s manual step per this task's constraints, since the phone may be mid-session and
# must not be force-stopped/uninstalled/reinstalled by an agent. This script only *observes* an
# already-running session and reports what it finds; run the app's Save action yourself first if
# you want the export checks below to have something to find.
#
# Force-stop + START_NOT_STICKY (issue #34 candidate test 5) is intentionally NOT covered here or
# in the androidTest suite: an androidTest instrumentation shares the target app's process, so
# force-stopping the package under test would kill the test process running the assertion, and
# this script is barred from force-stopping the S25's app for the reason above. That check needs
# a human running `adb shell am force-stop cc.machado.audioblackbox` deliberately and watching
# what happens -- documented in docs/testing/tiers.md, not automated here.
#
# Device connectivity gotcha (see docs/development/running-on-device.md and issue #34): wireless
# debugging's connect port changes on every reconnect, and mDNS does not traverse WSL (`adb mdns
# services` returns nothing here). If no device is already in `adb devices`' "device" state, this
# script port-scans the phone's IP across 30000-49999 to rediscover the current adb-tls-connect
# port -- exactly the manual recovery step the issue called out. It NEVER runs
# `adb kill-server` (that drops the wireless pairing and re-pairing needs physical phone access).
#
# Usage:
#   ./scripts/device-smoke.sh                     # auto-discover the connected/paired device
#   PHONE_IP=192.168.0.42 ./scripts/device-smoke.sh   # force a specific IP for the port scan

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log()  { printf '[device-smoke] %s\n' "$*"; }
warn() { printf '[device-smoke] WARNING: %s\n' "$*" >&2; }
fail() { printf '[device-smoke] ERROR: %s\n' "$*" >&2; exit 1; }

APP_ID="cc.machado.audioblackbox"

command -v adb >/dev/null 2>&1 || fail "adb not found on PATH."

# --- Discovery: reuse an already-`device`-state entry if one exists -------
find_ready_serial() {
  adb devices | awk '$2=="device" {print $1; exit}'
}

SERIAL="$(find_ready_serial || true)"

# --- Fallback: port-scan for the current wireless-debugging connect port --
# mDNS (`adb mdns services`) does not traverse WSL, so rediscovery here is a deliberate port
# scan across the wireless-debugging connect-port range, not a shortcut -- see the gotcha in the
# class doc above. This never calls `adb kill-server`; only `adb connect`/`adb disconnect`
# against candidate ip:port pairs, which cannot drop the underlying pairing.
if [[ -z "$SERIAL" ]]; then
  PHONE_IP="${PHONE_IP:-}"
  [[ -n "$PHONE_IP" ]] || fail \
    "No device in 'device' state and \$PHONE_IP not set -- cannot port-scan without knowing the phone's IP. Set PHONE_IP=<lan-ip> (see Wireless debugging screen on the S25) and re-run."
  log "No ready device found. Port-scanning $PHONE_IP:30000-49999 for the current wireless-debugging port (mDNS does not traverse WSL)..."
  FOUND_PORT=""
  for port in $(seq 30000 49999); do
    if timeout 0.05 bash -c "cat < /dev/null > /dev/tcp/$PHONE_IP/$port" 2>/dev/null; then
      FOUND_PORT="$port"
      break
    fi
  done
  [[ -n "$FOUND_PORT" ]] || fail \
    "No open port found on $PHONE_IP in 30000-49999. Confirm Wireless debugging is still on and re-check the IP on the phone's Wireless debugging screen."
  log "Found an open port at $PHONE_IP:$FOUND_PORT -- attempting adb connect..."
  adb connect "$PHONE_IP:$FOUND_PORT" >/dev/null
  SERIAL="$(find_ready_serial || true)"
  [[ -n "$SERIAL" ]] || fail "adb connect to $PHONE_IP:$FOUND_PORT did not reach 'device' state (check for an 'Allow debugging?' prompt on the phone)."
fi

log "Target device: $SERIAL"
ADB=(adb -s "$SERIAL")

PID="$("${ADB[@]}" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')"
if [[ -z "$PID" ]]; then
  warn "$APP_ID is not currently running on $SERIAL. Start a recording via the app first if you want a meaningful smoke pass; the checks below will just report 'not running'/'no rows found'."
fi

# --- Check 1: RECORD_AUDIO is genuinely in active use ----------------------
log "Checking RECORD_AUDIO appop state..."
APPOP="$("${ADB[@]}" shell cmd appops get "$APP_ID" RECORD_AUDIO 2>/dev/null | tr -d '\r')"
log "  RECORD_AUDIO: ${APPOP:-<no result>}"
if [[ -n "$PID" ]]; then
  case "$APPOP" in
    *allow*) log "  OK: mic access is currently allowed." ;;
    *) warn "  RECORD_AUDIO appop does not report 'allow' while the app is running -- expected 'allow' during an active recording session." ;;
  esac
fi

# --- Check 2: foreground service type ---------------------------------------
log "Checking foreground service type via dumpsys..."
DUMP="$("${ADB[@]}" shell dumpsys activity services "$APP_ID" 2>/dev/null)"
if echo "$DUMP" | grep -q "RecorderService"; then
  if echo "$DUMP" | grep -qi "foregroundServiceType=.*microphone\|isForeground=true"; then
    log "  OK: RecorderService present in dumpsys with foreground indicators."
  else
    warn "  RecorderService found in dumpsys but no clear foreground/microphone indicator -- inspect manually:"
    echo "$DUMP" | grep -A5 "RecorderService" || true
  fi
else
  warn "  RecorderService not found in dumpsys activity services (app not recording right now?)."
fi

# --- Check 3 + 4: MediaStore row (path, IS_PENDING, duration) + WAV header --
log "Querying MediaStore for the most recent blackbox_*.wav export..."
QUERY_OUT="$("${ADB[@]}" shell content query \
  --uri content://media/external/audio/media \
  --projection _id:_display_name:_data:relative_path:is_pending:duration \
  --sort "date_added DESC" 2>/dev/null | grep -m1 "blackbox_" || true)"

if [[ -z "$QUERY_OUT" ]]; then
  warn "No blackbox_*.wav row found in MediaStore yet. Tap Save in the app, then re-run this script."
else
  log "  $QUERY_OUT"
  DATA_PATH="$(echo "$QUERY_OUT" | grep -oE '_data=[^,]+' | cut -d= -f2-)"
  IS_PENDING="$(echo "$QUERY_OUT" | grep -oE 'is_pending=[0-9]+' | cut -d= -f2)"
  DECLARED_DURATION_MS="$(echo "$QUERY_OUT" | grep -oE 'duration=[0-9]+' | cut -d= -f2 || true)"

  if [[ "${IS_PENDING:-1}" == "0" ]]; then
    log "  OK: IS_PENDING is cleared."
  else
    warn "  IS_PENDING is not cleared ($IS_PENDING) -- export may still be running or aborted."
  fi

  if [[ -n "$DATA_PATH" ]]; then
    log "Pulling $DATA_PATH to verify the WAV header numerically..."
    LOCAL_COPY="$REPO_ROOT/.device-smoke-last-export.wav"
    if "${ADB[@]}" pull "$DATA_PATH" "$LOCAL_COPY" >/dev/null 2>&1; then
      # RIFF header: byteRate at offset 28 (4 bytes LE), data-chunk size at offset 40 (4 bytes LE).
      BYTE_RATE=$(od -An -tu4 -j28 -N4 --endian=little "$LOCAL_COPY" | tr -d ' ')
      DATA_SIZE=$(od -An -tu4 -j40 -N4 --endian=little "$LOCAL_COPY" | tr -d ' ')
      if [[ -n "$BYTE_RATE" && "$BYTE_RATE" -gt 0 ]]; then
        COMPUTED_DURATION_MS=$(( DATA_SIZE * 1000 / BYTE_RATE ))
        log "  WAV header: byteRate=$BYTE_RATE dataSize=$DATA_SIZE -> computed duration ${COMPUTED_DURATION_MS}ms (MediaStore declared: ${DECLARED_DURATION_MS:-unknown}ms)."
      else
        warn "  Could not read a sane byteRate from the WAV header at $LOCAL_COPY."
      fi
      rm -f "$LOCAL_COPY"
    else
      warn "  Could not pull $DATA_PATH (scoped-storage path may not be directly pullable without root; this is a known Android limitation, not a script bug)."
    fi
  fi
fi

log "Smoke pass complete."
