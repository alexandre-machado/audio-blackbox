#!/usr/bin/env bash
# Execute on the owner's trusted machine; never source or trace this script.
set +x
set -euo pipefail
umask 077

[[ -n ${CLOUDFLARE_API_TOKEN:-} ]] || { printf 'Set the owner-only master CLOUDFLARE_API_TOKEN first.\n' >&2; exit 1; }
[[ ${CI:-false} != true && ${GITHUB_ACTIONS:-false} != true ]] || { printf 'Owner machine only; never CI.\n' >&2; exit 1; }
# Provider debug logs can include credentials or API responses.
for log_var in ${!TF_LOG@}; do unset "$log_var"; done
action=${1:-apply}
[[ $# -le 1 ]] || { printf 'Usage: %s [apply|rotate|destroy]\n' "$0" >&2; exit 1; }
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
case "$action" in
  apply) terraform -chdir=infra/terraform/cloudflare-ci-token apply ;;
  rotate) terraform -chdir=infra/terraform/cloudflare-ci-token apply -replace=cloudflare_account_token.ci ;;
  destroy) terraform -chdir=infra/terraform/cloudflare-ci-token destroy; exit ;;
  *) printf 'Usage: %s [apply|rotate|destroy]\n' "$0" >&2; exit 1 ;;
esac
trap 'unset token_value' EXIT
if ! token_value=$(terraform -chdir=infra/terraform/cloudflare-ci-token output -raw token_value); then
  printf 'Could not read deploy token; GitHub secret was not changed.\n' >&2
  exit 1
fi
# Cloudflare API tokens are URL-safe and at least 40 characters (the format may grow); reject
# empty or error output without risking a valid, already-created token being refused.
if [[ ! $token_value =~ ^[A-Za-z0-9_-]{40,128}$ ]]; then
  printf 'Invalid deploy token output; GitHub secret was not changed.\n' >&2
  exit 1
fi
printf '%s' "$token_value" |
  gh secret set CLOUDFLARE_API_TOKEN --env cloudflare-hotsite --repo alexandre-machado/audio-blackbox >/dev/null
unset token_value
