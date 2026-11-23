#!/usr/bin/env bash
# Run from any directory: stage the rendered Pages artifact, never Jekyll source.
# Destination is fixed so cleanup cannot delete an arbitrary caller-supplied path.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
site_dir="${1:-${repo_root}/_site}"
bash "${repo_root}/scripts/ci/check-site-rendered.sh" "$site_dir"
test -f "${site_dir}/404.html" || { echo '::error::404.html missing'; exit 1; }
stage="${repo_root}/cloudflare/.staged"
rm -rf "$stage"
mkdir -p "${stage}/audio-blackbox"
cp -a "${site_dir}/." "${stage}/audio-blackbox/"
cp "${repo_root}/cloudflare/_headers" "${stage}/_headers"
echo "Staged rendered site at ${stage}/audio-blackbox/"
