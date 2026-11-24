#!/usr/bin/env bash
# Oracle: the real pinned provider must decode API arrays and plan exactly the
# account Scripts Write policy and the machado.cc Routes Write + Zone Read policy.
# No owner state, credentials, overrides, or Terraform CLI configuration are used.
set +x
set -euo pipefail
umask 077
exec python3 "$(dirname -- "${BASH_SOURCE[0]}")/test-cloudflare-ci-token-plan.py" "$@"
