#!/usr/bin/env bash
# Owner machine only. Never source this file or run it with shell tracing.
set +x
set -euo pipefail
trap 'unset CLOUDFLARE_MASTER_TOKEN response policy groups' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export -n CLOUDFLARE_MASTER_TOKEN 2>/dev/null || true

die() { printf 'Error: %s\n' "$*" >&2; exit 1; }
dry_run=false
set_account_var=false
account_id=${CLOUDFLARE_ACCOUNT_ID:-}
expires_on=
while (($#)); do
    case "$1" in
        --dry-run) dry_run=true; shift ;;
        --set-account-var) set_account_var=true; shift ;;
        --account-id|--expires-on)
            (($# >= 2)) || die "$1 requires a value"
            case "$1" in
                --account-id) account_id=$2 ;;
                --expires-on) expires_on=$2 ;;
            esac
            shift 2 ;;
        --help)
            printf '%s\n' 'Usage: bash scripts/ops/cloudflare-create-deploy-token.sh [--dry-run] [--account-id ID] [--expires-on YYYY-MM-DDTHH:MM:SSZ] [--set-account-var]'
            exit 0 ;;
        *) die 'Unknown argument (see --help)' ;;
    esac
done
for dep in curl jq gh; do
    command -v "$dep" >/dev/null || die "Required dependency missing: $dep"
done
if [[ -z $expires_on ]]; then
    expires_on=$(jq -nr 'now + 365*86400 | strftime("%Y-%m-%dT%H:%M:%SZ")')
fi
jq -en --arg expiry "$expires_on" '
    ($expiry | strptime("%Y-%m-%dT%H:%M:%SZ") | mktime) > now
' >/dev/null 2>&1 || die '--expires-on must be a future UTC timestamp YYYY-MM-DDTHH:MM:SSZ'

if $dry_run; then
    account_id=ACCOUNT_ID
    zone_id=ZONE_ID
    scripts_id=WORKERS_SCRIPTS_WRITE_ID
    routes_id=WORKERS_ROUTES_WRITE_ID
    zone_read_id=ZONE_READ_ID
else
    [[ ${CI:-false} != true && ${GITHUB_ACTIONS:-false} != true ]] || die 'Run only on the owner machine, never CI'
    [[ -n ${CLOUDFLARE_MASTER_TOKEN:-} ]] || die 'Set CLOUDFLARE_MASTER_TOKEN through a hidden prompt first'
    [[ $CLOUDFLARE_MASTER_TOKEN =~ ^[A-Za-z0-9_-]+$ ]] || die 'Invalid master token characters'
    [[ $account_id =~ ^[a-fA-F0-9]{32}$ ]] || die 'Supply a 32-hex account ID via --account-id or CLOUDFLARE_ACCOUNT_ID'
    gh auth status >/dev/null 2>&1 || die 'Authenticate gh on the owner machine first'
fi

# Header travels through a pipe descriptor, never argv. Disable curl config files,
# redirects, retries and response diagnostics; API error bodies may contain secrets.
api() {
    curl -q --silent --fail --proto '=https' --connect-timeout 15 --max-time 60 \
        --header @/dev/fd/3 --header 'Content-Type: application/json' \
        "https://api.cloudflare.com/client/v4$1" "${@:2}" \
        3<<< "Authorization: Bearer $CLOUDFLARE_MASTER_TOKEN" 2>/dev/null
}
permission_id() {
    jq -er --arg name "$1" --arg scope "$2" '
        [.result[] | select(.name == $name and .is_selectable != false)
          | select((.scopes // []) | index($scope))] |
        if length == 1 then .[0].id | select(test("^[a-fA-F0-9]{32}$"))
        else error("Permission missing, ambiguous or unavailable") end
    ' <<< "$groups" 2>/dev/null || die "Cannot resolve selectable permission: $1 ($2)"
}
if ! $dry_run; then
    response=$(api "/zones?name=machado.cc&account.id=$account_id") || die 'Zone lookup failed; master needs Zone Read on machado.cc'
    zone_id=$(jq -er --arg account "$account_id" '
        select(.success == true) | .result |
        if length == 1 and .[0].name == "machado.cc" and .[0].account.id == $account
        then .[0].id | select(test("^[a-fA-F0-9]{32}$")) else empty end
    ' <<< "$response" 2>/dev/null) || die 'Expected exactly one machado.cc zone in this account'
    groups=$(api "/accounts/$account_id/tokens/permission_groups") || die 'Permission lookup failed; master needs Account API Tokens Write'
    jq -e '.success == true and (.result | type == "array")' <<< "$groups" >/dev/null 2>&1 || die 'Invalid permission lookup response'
    scripts_id=$(permission_id 'Workers Scripts Write' com.cloudflare.api.account)
    routes_id=$(permission_id 'Workers Routes Write' com.cloudflare.api.account.zone)
    zone_read_id=$(permission_id 'Zone Read' com.cloudflare.api.account.zone)
fi

policy=$(jq -n --arg account "$account_id" --arg zone "$zone_id" \
    --arg scripts "$scripts_id" --arg routes "$routes_id" --arg read "$zone_read_id" \
    --arg expiry "$expires_on" '{name: "audio-blackbox-hotsite-deploy", expires_on: $expiry,
      policies: [
        {effect: "allow", resources: {("com.cloudflare.api.account." + $account): "*"},
         permission_groups: [{id: $scripts}]},
        {effect: "allow", resources: {("com.cloudflare.api.account.zone." + $zone): "*"},
         permission_groups: [{id: $routes}, {id: $read}]}
      ]}')
repo=alexandre-machado/audio-blackbox
environment=cloudflare-hotsite
if $dry_run; then
    printf '%s\n' "$policy"
    printf '%s\n' 'Resolve: Workers Scripts Write (account); Workers Routes Write (zone); Zone Read (zone)' >&2
    printf '%s\n' "[REDACTED secret via stdin] | gh secret set CLOUDFLARE_API_TOKEN --env $environment --repo $repo" >&2
    if $set_account_var; then
        printf '%s\n' "gh variable set CLOUDFLARE_ACCOUNT_ID --env $environment --repo $repo --body ACCOUNT_ID" >&2
    fi
    exit 0
fi
response=$(api "/accounts/$account_id/tokens" --request POST --data "$policy") || die 'Token creation failed or outcome unknown; inspect account tokens before retrying'
# Validate before starting gh, so an API failure cannot overwrite a working secret.
jq -e '.success == true and (.result.value | type == "string" and test("^[A-Za-z0-9_-]{40,80}$"))' \
    <<< "$response" >/dev/null 2>&1 || die 'No valid token value returned; inspect account tokens before retrying'
if ! jq -jr '.result.value' <<< "$response" | \
    gh secret set CLOUDFLARE_API_TOKEN --env "$environment" --repo "$repo" >/dev/null 2>&1; then
    die 'GitHub secret upload failed; revoke the newly created Cloudflare token before retrying'
fi
unset response CLOUDFLARE_MASTER_TOKEN
if $set_account_var; then
    gh variable set CLOUDFLARE_ACCOUNT_ID --env "$environment" --repo "$repo" --body "$account_id"
fi
printf '%s\n' 'Deployment token stored in cloudflare-hotsite. Revoke the temporary master token now.'
