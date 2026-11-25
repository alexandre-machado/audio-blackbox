#!/usr/bin/env bash
# Ruby toolchain for the hosted Pages Jekyll build (#480/#479).
# Usage: scripts/ci/setup-jekyll-ruby.sh <fresh RUNNER_TEMP tool-root>
# Prints the env's bin dir on stdout (last line); callers add it to PATH.
# Each hosted job creates a fresh relocatable conda-forge environment. Both the
# micromamba binary and every package (including transitive dependencies) are
# SHA-256 pinned. The explicit linux-64 lock avoids a mutable channel solve.
# This runs in the read-only build job, without Pages/OIDC deployment credentials.
set -euo pipefail

MICROMAMBA_VERSION="2.9.0-0"
MICROMAMBA_SHA256="366cd9cd8be14df1ab8ed50352a82111082a36686b2d389fdb79a92c3fafb3e3"
RUBY_ENV_KEY="ruby-3.3.6-cc-2.0.0-v3"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
lockfile="${script_dir}/jekyll-ruby-linux-64.lock"

tool_root="${1:?usage: $0 <tool-root>}"
mm_dir="${tool_root}/micromamba-${MICROMAMBA_VERSION}"
mm="${mm_dir}/micromamba"
env_dir="${tool_root}/${RUBY_ENV_KEY}"

log() { echo "$*" >&2; }

# The micromamba binary is re-verified on every run, cached or not.
if ! { [ -x "$mm" ] && echo "${MICROMAMBA_SHA256}  ${mm}" | sha256sum -c --quiet - >/dev/null 2>&1; }; then
  log "Fetching micromamba ${MICROMAMBA_VERSION}"
  mkdir -p "$mm_dir"
  curl -fsSL -o "${mm}.tmp" \
    "https://github.com/mamba-org/micromamba-releases/releases/download/${MICROMAMBA_VERSION}/micromamba-linux-64"
  echo "${MICROMAMBA_SHA256}  ${mm}.tmp" | sha256sum -c - >&2
  chmod +x "${mm}.tmp"
  mv "${mm}.tmp" "$mm"
fi

# RUNNER_TEMP is fresh; refuse accidental reuse rather than trust mutable env files.
if [ -e "$env_dir" ]; then
  log "Expected a fresh tool root; environment already exists: ${env_dir}"
  exit 1
fi
log "Creating Ruby env ${env_dir} from SHA-256 lock"
MAMBA_ROOT_PREFIX="${tool_root}/mamba-root" \
  "$mm" create --no-rc -y -q -p "$env_dir" -f "$lockfile" >&2
"${env_dir}/bin/ruby" -v >&2

echo "${env_dir}/bin"
