#!/usr/bin/env bash
# Run from any directory: stage generated static assets, never source templates.
# Destination is fixed so cleanup cannot delete an arbitrary caller-supplied path.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
site_dir="${1:-${repo_root}/site/dist}"
# The Node build/test gate validates the generated site before staging.
test -f "${site_dir}/404.html" || { echo '::error::404.html missing'; exit 1; }
stage="${repo_root}/cloudflare/.staged"
rm -rf "$stage"
mkdir -p "${stage}/audio-blackbox"
cp -a "${site_dir}/." "${stage}/audio-blackbox/"
cp "${stage}/audio-blackbox/404.html" "${stage}/404.html"
echo "Staged rendered site at ${stage}/audio-blackbox/"
