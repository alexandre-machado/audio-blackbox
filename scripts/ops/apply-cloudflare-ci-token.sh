#!/usr/bin/env bash
# Execute on the owner's trusted machine; never source or trace this script.
set +x
set -euo pipefail
umask 077

[[ -n ${CLOUDFLARE_API_TOKEN:-} ]] || { printf 'Set the owner-only master CLOUDFLARE_API_TOKEN first.\n' >&2; exit 1; }
[[ ${CI:-false} != true && ${GITHUB_ACTIONS:-false} != true ]] || { printf 'Owner machine only; never CI.\n' >&2; exit 1; }
# Provider debug logs can include credentials or API responses.
unset TF_LOG TF_LOG_PATH TF_LOG_CORE TF_LOG_PROVIDER
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
terraform -chdir=infra/terraform/cloudflare-ci-token apply
terraform -chdir=infra/terraform/cloudflare-ci-token output -raw token_value |
  gh secret set CLOUDFLARE_API_TOKEN --env cloudflare-hotsite --repo alexandre-machado/audio-blackbox >/dev/null
