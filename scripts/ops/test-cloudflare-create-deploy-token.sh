#!/usr/bin/env bash
set -euo pipefail
script="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/cloudflare-create-deploy-token.sh"
expiry=2099-01-01T00:00:00Z
# Oracle: the real builder must emit exactly two narrowly scoped allow policies,
# all three required permissions, the requested expiry and the fixed token name.
output=$(env -u CLOUDFLARE_MASTER_TOKEN -u CLOUDFLARE_ACCOUNT_ID \
    bash "$script" --dry-run --expires-on "$expiry" 2>/dev/null)
jq -e --arg expiry "$expiry" '
    .name == "audio-blackbox-hotsite-deploy" and .expires_on == $expiry and
    (.policies | length == 2) and
    all(.policies[]; .effect == "allow") and
    .policies[0].resources == {"com.cloudflare.api.account.ACCOUNT_ID": "*"} and
    .policies[1].resources == {"com.cloudflare.api.account.zone.ZONE_ID": "*"} and
    .policies[0].permission_groups == [{id: "WORKERS_SCRIPTS_WRITE_ID"}] and
    .policies[1].permission_groups == [{id: "WORKERS_ROUTES_WRITE_ID"}, {id: "ZONE_READ_ID"}]
' <<< "$output" >/dev/null
# Oracle: even an inherited master never appears on either output channel;
# dry-run remains credential independent and reports the exact intended commands.
sentinel=TEST_MASTER_MUST_NEVER_APPEAR
combined=$(CLOUDFLARE_MASTER_TOKEN=$sentinel bash "$script" --dry-run --set-account-var 2>&1)
[[ $combined != *"$sentinel"* ]]
for name in 'Workers Scripts Write' 'Workers Routes Write' 'Zone Read'; do
    [[ $combined == *"$name"* ]]
done
[[ $combined == *'[REDACTED secret via stdin] | gh secret set CLOUDFLARE_API_TOKEN --env cloudflare-hotsite --repo alexandre-machado/audio-blackbox'* ]]
[[ $combined == *'gh variable set CLOUDFLARE_ACCOUNT_ID --env cloudflare-hotsite --repo alexandre-machado/audio-blackbox --body ACCOUNT_ID'* ]]
without_var=$(bash "$script" --dry-run 2>&1)
[[ $without_var != *'gh variable set'* ]]
if env -u CLOUDFLARE_MASTER_TOKEN bash "$script" --account-id 00000000000000000000000000000000 >/dev/null 2>&1; then
    printf '%s\n' 'FAIL: real mode accepted missing master token' >&2
    exit 1
fi
printf '%s\n' 'PASS: dry-run JSON, narrow scopes, three permissions, expiry, redaction, opt-in variable, missing-master refusal'
