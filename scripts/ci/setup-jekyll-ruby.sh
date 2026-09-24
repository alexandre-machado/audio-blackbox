#!/usr/bin/env bash
# Ruby toolchain for the Pages Jekyll build (issue #414), for the self-hosted WSL runner.
#
# Usage: scripts/ci/setup-jekyll-ruby.sh <tool-root>
# Prints the env's bin dir on stdout (last line); callers add it to PATH.
#
# Why this shape: the runner has no Docker daemon (so actions/jekyll-build-pages can't run) and
# no sudo. ruby/setup-ruby needs a writable /opt/hostedtoolcache or a hand-installed Ruby in the
# tool cache, and a relocated ruby-builder tarball can't find its own stdlib. conda-forge's Ruby
# is relocatable and ships its own C toolchain for native gems, so a pinned, sha256-verified
# micromamba installs it with no host changes.
#
# Reuse is verified, not trusted (#415 review): the env is kept across runs, but at creation
# we record a manifest (sha256 of every regular file, plus every symlink and its target). On
# reuse, the env is rebuilt from scratch if any file changed, went missing or was added, if a
# symlink changed, or if `ruby -v` fails. This catches corruption and partial deletion (e.g.
# tool-cache cleanup). It is NOT a defense against a same-user attacker, who can rewrite the
# manifest too. That risk is bounded by pages.yml's job split: this toolchain runs in a job
# with contents: read only, never with pages: write or the OIDC token.
set -euo pipefail

MICROMAMBA_VERSION="2.9.0-0"
MICROMAMBA_SHA256="366cd9cd8be14df1ab8ed50352a82111082a36686b2d389fdb79a92c3fafb3e3"
RUBY_ENV_SPEC=(ruby=3.3.6 c-compiler=2.0.0 cxx-compiler=2.0.0 make=4.4.1)
# Bump the suffix to force a rebuild after changing RUBY_ENV_SPEC.
RUBY_ENV_KEY="ruby-3.3.6-cc-2.0.0-v2"

tool_root="${1:?usage: $0 <tool-root>}"
mm_dir="${tool_root}/micromamba-${MICROMAMBA_VERSION}"
mm="${mm_dir}/micromamba"
env_dir="${tool_root}/${RUBY_ENV_KEY}"
manifest="${env_dir}.files.sha256"
links="${env_dir}.links.txt"

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

list_files() { (cd "$env_dir" && find . -type f -print0 | LC_ALL=C sort -z); }
list_links() { (cd "$env_dir" && find . -type l -printf '%p -> %l\n' | LC_ALL=C sort); }

env_is_intact() {
  [ -f "$manifest" ] && [ -f "$links" ] && [ -d "$env_dir" ] || { log "env: no manifest"; return 1; }
  # Same set of files (catches additions and deletions)...
  if ! cmp -s <(list_files | tr '\0' '\n') <(sed 's/^[0-9a-f]\{64\}  //' "$manifest"); then
    log "env: file set differs from manifest"; return 1
  fi
  # ...with the same contents...
  if ! (cd "$env_dir" && sha256sum -c --quiet --strict "$manifest" >/dev/null 2>&1); then
    log "env: file contents differ from manifest"; return 1
  fi
  # ...the same symlinks, and a Ruby that actually runs.
  if ! cmp -s <(list_links) "$links"; then log "env: symlinks differ from manifest"; return 1; fi
  if ! "${env_dir}/bin/ruby" -v >/dev/null 2>&1; then log "env: ruby -v failed"; return 1; fi
  return 0
}

if env_is_intact; then
  log "Reusing verified Ruby env ${env_dir}"
else
  log "Creating Ruby env ${env_dir}"
  rm -rf "$env_dir" "$manifest" "$links"
  MAMBA_ROOT_PREFIX="${tool_root}/mamba-root" \
    "$mm" create -y -q -p "$env_dir" -c conda-forge --override-channels "${RUBY_ENV_SPEC[@]}" >&2
  "${env_dir}/bin/ruby" -v >&2
  (cd "$env_dir" && list_files | xargs -0 -r sha256sum) > "${manifest}.tmp"
  list_links > "${links}.tmp"
  mv "${links}.tmp" "$links"
  mv "${manifest}.tmp" "$manifest"
fi

echo "${env_dir}/bin"
