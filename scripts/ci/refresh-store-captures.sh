#!/usr/bin/env bash
# Single source of truth for "which CI capture goes where" (issue #231).
#
# scripts/ci/run-instrumented-tier.sh pulls ScreenshotCaptureTest's (issue #78) PNGs off the
# emulator into build/screen-captures/ (see ScreenshotCaptureTest.kt:225-229's own comment: "Must
# match the directory names under distribution/metadata/android/" -- that contract existed before
# this script did, it was just never enforced). This script is the enforcement: it copies those
# captures onto the nine committed destinations that the Play Store listing
# (scripts/ci/sync-play-store-metadata.py) and the hotsite (docs/index.html) are built from.
#
# Nine destinations, six distinct sources -- the hotsite set intentionally reuses the en-US
# showcase captures rather than having its own, so this table is the only place that fact needs to
# be known (verified against the images committed by #227 and #230; see issue #231's own
# measurement of the mapping before trusting this comment blindly).
#
# Fails loudly, on purpose, if any expected source file is missing: a partial refresh that
# silently skips a file is the exact failure mode issue #231 exists to prevent. Usable both from
# CI (.github/workflows/ci.yml's instrumented-tests job) and locally, against any
# build/screen-captures/ directory populated the same way (e.g. by
# scripts/run-instrumented-tests.sh).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

log()  { printf '[refresh-store-captures] %s\n' "$*"; }
fail() { printf '[refresh-store-captures] ERROR: %s\n' "$*" >&2; exit 1; }

CAPTURES_DIR="${1:-build/screen-captures}"

if [[ ! -d "$CAPTURES_DIR" ]]; then
  fail "Captures directory '$CAPTURES_DIR' does not exist. Run scripts/ci/run-instrumented-tier.sh (or scripts/run-instrumented-tests.sh locally) first."
fi

# source (relative to $CAPTURES_DIR) -> destination (relative to repo root).
# Keep in sync with ScreenshotCaptureTest.kt's naming and the mapping verified in issue #231.
MAPPING=(
  "en-US-01-dashboard.png:distribution/metadata/android/en-US/images/phoneScreenshots/1_dashboard.png"
  "en-US-02-gallery.png:distribution/metadata/android/en-US/images/phoneScreenshots/2_gallery.png"
  "en-US-03-settings.png:distribution/metadata/android/en-US/images/phoneScreenshots/3_settings.png"
  "pt-BR-01-dashboard.png:distribution/metadata/android/pt-BR/images/phoneScreenshots/1_dashboard.png"
  "pt-BR-02-gallery.png:distribution/metadata/android/pt-BR/images/phoneScreenshots/2_gallery.png"
  "pt-BR-03-settings.png:distribution/metadata/android/pt-BR/images/phoneScreenshots/3_settings.png"
  "en-US-01-dashboard.png:docs/assets/screenshot_dashboard.png"
  "en-US-02-gallery.png:docs/assets/screenshot_gallery.png"
  "en-US-03-settings.png:docs/assets/screenshot_settings.png"
)

# Fail loudly up front if any source is missing, rather than copying the ones that do exist and
# silently leaving stale destinations for the ones that don't -- that partial-refresh shape is
# exactly what let #228 go unnoticed for two feature landings.
MISSING=()
for entry in "${MAPPING[@]}"; do
  src="${entry%%:*}"
  if [[ ! -f "$CAPTURES_DIR/$src" ]]; then
    MISSING+=("$CAPTURES_DIR/$src")
  fi
done
if [[ "${#MISSING[@]}" -gt 0 ]]; then
  fail "Missing expected source capture(s): ${MISSING[*]}. Refusing a partial refresh -- see run-instrumented-tier.sh's SCREENSHOT_OUT_DIR / ScreenshotCaptureTest for why these should exist."
fi

log "All ${#MAPPING[@]} expected source captures present under $CAPTURES_DIR. Refreshing destinations..."
for entry in "${MAPPING[@]}"; do
  src="${entry%%:*}"
  dst="${entry##*:}"
  mkdir -p "$(dirname "$dst")"
  cp -f "$CAPTURES_DIR/$src" "$dst"
  log "  $CAPTURES_DIR/$src -> $dst"
done

log "Done."
