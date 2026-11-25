# Cloudflare hotsite operator runbook

The public address remains **https://alexandre.machado.cc/audio-blackbox/**.
The Play Console privacy-policy URL remains
**https://alexandre.machado.cc/audio-blackbox/release/privacy-policy**.
No domain migration or Play Console edit is needed. GitHub Pages remains the
origin and rollback target; the user site's `/` is outside the Worker route.

## 1. Provision the deployment token with Terraform (owner only)

Terraform in `infra/terraform/cloudflare-ci-token/` is the supported provisioning
path. The [dashboard template link](https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/) exists but is not the supported path.

Prerequisites: Terraform **1.17.2**, Bash, GitHub CLI authenticated with permission
to set environment secrets, and a trusted owner machine. Prepare the protected
`cloudflare-hotsite` environment in section 2 first. Set its non-secret
`CLOUDFLARE_ACCOUNT_ID` variable to `53f6d9e3dd6600e598c81e222c4369db`.
The wrapper does not create the environment or change its protection rules.

Create a temporary master token in the Cloudflare dashboard with only:

- **Account > Account API Tokens > Edit** (`Account API Tokens Write`), scoped
  to account `53f6d9e3dd6600e598c81e222c4369db`.
- **Zone > Zone > Read**, scoped only to **machado.cc**, for zone lookup.
- **Zone > Workers Routes > Edit** (`Workers Routes Write`), scoped only to
  **machado.cc**, so the master can delegate that single-zone permission.

Cloudflare's single-zone common permission check requires the creating token
to hold each permission it grants on that zone (both Zone Read and Workers
Routes Write here). [Upstream #7241](https://github.com/cloudflare/terraform-provider-cloudflare/issues/7241)
reports that account-scoped groups are delegated through Account API Tokens
Write without this requirement; the maintainer confirms the check is API-side.
Accordingly, **Workers Scripts Edit on the master is not required** for this
account-scoped grant. This is upstream evidence, not a live authorization test
of this module; do not broaden the child token to all zones to bypass the check.

Use a short expiry (for example one hour). The creating member needs API Token
Provisioning capability or Super Administrator status and authority to grant the
requested permissions; see [account-owned tokens](https://developers.cloudflare.com/fundamentals/api/get-started/account-owned-tokens/).
The master is owner-only: never CI, never committed, never a GitHub secret.
`CLOUDFLARE_API_TOKEN` in the owner's shell is the **master**; the GitHub secret of
that same name receives only the restricted **deploy** token.

From the repository root in a fresh, untraced Bash shell:

```bash
set +x
umask 077
unset TF_LOG TF_LOG_PATH TF_LOG_CORE TF_LOG_PROVIDER
terraform -chdir=infra/terraform/cloudflare-ci-token init -lockfile=readonly
read -r -s -p 'Temporary Cloudflare master token: ' CLOUDFLARE_API_TOKEN
printf '\n'
export CLOUDFLARE_API_TOKEN
bash scripts/ops/apply-cloudflare-ci-token.sh
unset CLOUDFLARE_API_TOKEN
```

Review the interactive apply plan before approving it. Always unset the parent
shell variable even if apply/upload fails, and immediately revoke the temporary
master in the Cloudflare dashboard; unsetting it does not revoke it. Do not use
shell tracing, Terraform debug logs, `terraform output -raw` on its own, or
`terraform show -json` in recorded terminals. The wrapper disables Terraform
logging, applies with restrictive file permissions, and pipes the sensitive
output to `gh secret set` via stdin, never a command argument or a file. It first
checks that Terraform output succeeded and contains a non-empty token of 40-128 URL-safe characters, then clears the temporary shell variable after upload or failure.
Normal apply output redacts `token_value`; `sensitive` does not encrypt state.

The module creates **audio-blackbox-hotsite-ci** with exactly two allow policies:

| Permission names resolved through the API | Resource |
| --- | --- |
| Workers Scripts Write (dashboard Edit) | Configured account only |
| Workers Routes Write (dashboard Edit), Zone Read | machado.cc only |

Permission names and scopes must resolve to exactly one selectable group each;
missing/ambiguous results fail a Terraform postcondition. The zone data source
filters by both name and account. Neither permission IDs nor zone IDs are
hardcoded. No DNS Edit, KV, R2, Pages, or all-zone grants are included.
Workers Scripts remains account-wide and Workers Routes remains zone-wide.

The pinned latest stable provider checked on 2026-11-24 is **5.29.0**. Schemas:
[`cloudflare_account_token`](https://registry.terraform.io/providers/cloudflare/cloudflare/5.29.0/docs/resources/account_token),
[`cloudflare_account_api_token_permission_groups_list`](https://registry.terraform.io/providers/cloudflare/cloudflare/5.29.0/docs/data-sources/account_api_token_permission_groups_list),
and [`cloudflare_zone`](https://registry.terraform.io/providers/cloudflare/cloudflare/5.29.0/docs/data-sources/zone).
The `_list` data source accepts optional `account_id`, `name`, `scope`, and
`max_items` (default 1000). Its `result` list contains `id`, `name`, `scopes`,
`category` and, per the schema, `is_selectable` (checked against the
[v5.29.0 registry source](https://github.com/cloudflare/terraform-provider-cloudflare/blob/v5.29.0/docs/data-sources/account_api_token_permission_groups_list.md)).
Pass raw names: the SDK URL-encodes them despite
the registry example showing encoded names. Terraform selects exactly one
exact-name, matching-scope group with a non-empty ID that is not explicitly
unselectable: `is_selectable = false` is rejected, but a missing or null
`is_selectable` is accepted, because the real API response on record (#7212 debug
log) omits the field. A missing name, scope or ID still fails closed, and if a group
is not grantable, Cloudflare itself refuses to create the token. The non-list data source cannot decode the API's
array response in 5.29.0 ([#7212](https://github.com/cloudflare/terraform-provider-cloudflare/issues/7212)).
The zone data source remains: its list-then-detail decoding is covered by the
mock plan test. The provider uses `CLOUDFLARE_API_TOKEN` automatically.
The generated `.terraform.lock.hcl` belongs in version control.

### Expiry, state, verification, rotation and revocation

`expires_on` defaults to **2027-11-24T00:00:00Z**, about one year after this
rollout, deliberately a fixed date so repeated applies do not move expiry.
Set `TF_VAR_expires_on` to another future RFC3339 timestamp when rotating; record
a reminder before expiry. `TF_VAR_account_id` can override the account default,
but machado.cc must resolve inside that account.

The default **local backend only** is intentional: state and state backups
contain the full token value. Keep them on the trusted owner machine with
restricted permissions/encrypted storage, only as long as needed to rotate or
destroy this token. Never upload state, saved plans, crash/debug logs, or secret
variable files to CI/artifacts. `.gitignore` excludes `*.tfstate*`, `.terraform/`,
crash logs, `*.tfvars`, `*.tfvars.json`, `*.tfplan`, Terraform CLI credential
configuration (`.terraformrc`, `terraform.rc`), and local override files
(`override.tf`, `override.tf.json`, `*_override.tf`, `*_override.tf.json`). Ignoring files is not
access control. Do not delete live state casually: it is needed to manage/revoke
the token. After destroy (or confirmed dashboard revocation), remove state and
backups according to the owner's secure-storage policy. Do not add a remote
backend without a separately reviewed secret-storage design.

Verify without displaying the value:

```bash
gh secret list --env cloudflare-hotsite --repo alexandre-machado/audio-blackbox
```

Confirm the token name, expiry and scopes in Cloudflare, then run the main-branch
deployment in section 3 and confirm success. No authenticated creation or deploy
has been tested as part of credential-free validation.

For rotation, obtain a fresh short-lived master with the same minimal scope,
load it with the hidden prompt above, and choose a new expiry:

```bash
export TF_VAR_expires_on='2027-11-24T00:00:00Z' # choose a future rotation date
bash scripts/ops/apply-cloudflare-ci-token.sh rotate
unset CLOUDFLARE_API_TOKEN TF_VAR_expires_on
```

Replacement revokes the old token before creating the new one, so pause deploys
until the wrapper uploads the replacement. Revoke the temporary master and
verify a main deployment. If GitHub upload fails after a successful apply, keep
the state and rerun the wrapper to upload the same value; do not create orphan
tokens. If creation fails with an uncertain outcome, inspect the dashboard and
revoke any orphan before retrying. Lost state cannot recover the one-time token
value: revoke the token in the dashboard and provision a replacement.

To retire it, load a fresh master, then run:

```bash
bash scripts/ops/apply-cloudflare-ci-token.sh destroy
unset CLOUDFLARE_API_TOKEN
gh secret delete CLOUDFLARE_API_TOKEN --env cloudflare-hotsite --repo alexandre-machado/audio-blackbox
```

Destroy revokes the deploy token in Cloudflare; deleting the GitHub secret alone
does not. Revoke the master too, then remove the local secret-bearing state and
backups. This module does not destroy the Worker or route.
Both subcommands enforce `umask 077`, disable tracing, unset every `TF_LOG*`
variable, and refuse CI just like the default apply operation. Destroy never
uploads a secret. To retry only an upload after rotation, use the default wrapper
operation, **not `rotate`**, which would replace the token again.

### Credential-free gate

```bash
terraform fmt -check -recursive
terraform -chdir=infra/terraform/cloudflare-ci-token init -backend=false -input=false -lockfile=readonly
terraform -chdir=infra/terraform/cloudflare-ci-token validate
bash scripts/ops/test-cloudflare-ci-token-plan.sh
# Also prove the regression oracle rejects both historical defects:
bash scripts/ops/test-cloudflare-ci-token-plan.sh --mutations
```

`Infrastructure Validation` (`.github/workflows/infrastructure.yml`, check
`Infrastructure / Validate Cloudflare token plan`) runs these checks on a fresh GitHub-hosted
runner for pull requests and main pushes, with read-only contents permission,
no environment or secrets, Terraform 1.17.2, and `hashicorp/setup-terraform`
v4.0.1 pinned to `dfe3c3f87815947d99a8997f908cb6525fc44e9e`.
The plan test uses Python 3's loopback HTTP server, canned API-shaped responses,
a fake token and disposable module copies with a clean Terraform environment.
Provider 5.29.0 supports `CLOUDFLARE_BASE_URL` (also the `base_url` argument);
see its [provider implementation](https://github.com/cloudflare/terraform-provider-cloudflare/blob/v5.29.0/internal/provider.go).
The test directs all API requests to that server. It checks the JSON plan for
exactly two policies, expected permission IDs and account/zone resource keys,
including similar-name, wrong-scope, unselectable and null-attribute decoys.
`--mutations` reinstates double encoding and the broken non-list data source
separately, requiring each to fail the permission postcondition after validation
passes. No real credentials or owner state are used. This tests provider
decoding and module behavior, not live authorization. Apply, rotation,
destruction, and deployment verification remain owner-only operations.

## 2. Configure GitHub Actions

In `alexandre-machado/audio-blackbox`, open **Settings > Environments** and create
**cloudflare-hotsite** yourself (Settings > Environments > New environment) **before the PR merges or before any credential exists**. The first push to main would otherwise auto-create it with no branch policy. Set **Deployment branches and tags** to **Selected branches: main only** and confirm the policy shows `main` before adding credentials. Add `CLOUDFLARE_API_TOKEN` as an **environment secret** and
`CLOUDFLARE_ACCOUNT_ID` as an **environment variable** (account ID, not zone ID).
Do not store the token as a repository secret. The workflow reads
`secrets.CLOUDFLARE_API_TOKEN` and `vars.CLOUDFLARE_ACCOUNT_ID` from this environment.
The deploy job also checks repository identity and `refs/heads/main`.
If either setting is empty, the final deploy step emits a notice and skips upload
successfully; credential-free preparation still runs. Only the single Wrangler
deploy step receives the token.

## 3. First deployment, before DNS cutover

Run **Actions > Site Build and Deploy > Run workflow**, selecting **main**, or push
a change under `docs/**` to main. Cloudflare configuration and staging-script
changes also trigger this workflow. Do not dispatch an older branch after cutover.

The build renders Jekyll in production mode, validates SEO/analytics and Liquid,
and uploads the `github-pages` artifact. The job-id graph is `build → deploy` (Pages)
and `build → validate-cloudflare → deploy-cloudflare`. Cloudflare/npm failures
cannot block Pages. Validation downloads the artifact, stages it, runs Worker
tests and a credential-free dry-run. Deployment consumes the same artifact;
its trust is identical to Pages, so it does not rerun Ruby or the Liquid guard.
Both Cloudflare jobs use fresh checkouts, pinned setup-node with a fresh tool
cache, fresh npm caches and temporary copies of `cloudflare/`. `npm ci
--ignore-scripts` installs Wrangler 4.160.0 and its full locked dependency tree;
they invoke the local binary. Dry-run isolates both HOME and XDG_CONFIG_HOME.
The existing `pages` concurrency group serializes deployments. There is only one
IndexNow submission, in Pages.

### Pre-cutover local check (no credentials)

Build Jekyll and run `bash scripts/ci/check-site-rendered.sh _site` first, then:

```bash
bash scripts/ci/stage-cloudflare-site.sh _site
cf_work="$(mktemp -d)"
cp -a cloudflare/. "$cf_work/"
export HOME="$(mktemp -d)" XDG_CONFIG_HOME="$(mktemp -d)"
export NPM_CONFIG_CACHE="$(mktemp -d)" WRANGLER_SEND_METRICS=false
unset CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_API_KEY CLOUDFLARE_EMAIL
npm ci --ignore-scripts --prefix "$cf_work"
"$cf_work/node_modules/.bin/wrangler" dev --config "$cf_work/wrangler.jsonc" --local --local-protocol https
```

In another terminal (local self-signed TLS only):

```bash
curl -ksSI https://localhost:8787/audio-blackbox/release/privacy-policy
curl -ksSI https://localhost:8787/audio-blackbox/assets/icon.png
curl -ksSI https://localhost:8787/audio-blackbox/llms.txt
curl -ksSI https://localhost:8787/audio-blackbox/pt-br
curl -ksS -D - https://localhost:8787/audio-blackbox/does-not-exist
```

Expect 200 for privacy/assets/text, a 301 absolute HTTPS Location ending in
`/pt-br/`, and 404 with the rendered custom `<h1>404</h1>` body and `noindex`.
All responses must carry the four security headers listed in section 5, without
HSTS. Check image TTL and text Content-Type too. This exercises the real local
asset binding, including custom 404 handling, before production traffic moves.

Deployment creates/updates Worker **audio-blackbox-site**, its static assets, and
zone route **alexandre.machado.cc/audio-blackbox*** in **machado.cc**.
There is no custom domain, workers.dev endpoint or preview URL. With the existing
DNS-only CNAME, traffic still goes directly to GitHub Pages until step 4.

## 4. Switch traffic: the owner's second step, a DNS change on the whole host

Proxying (orange cloud) is a **DNS change on `alexandre.machado.cc`, made by the owner**. It is the second step, after the API token, and it is separate from it. From then on **every** path on this host goes through Cloudflare, not just `/audio-blackbox`. The Worker answers only `/audio-blackbox*`. Every other path is passed on to GitHub Pages as before.

#### Sites served on this host today (checked 2026-11-23; all answer HTTP 200)
| Path | Source (GitHub Pages) | Enforce HTTPS on GitHub |
|---|---|---|
| `/` | `alexandre-machado.github.io` (user site, holds the `alexandre.machado.cc` custom domain) | yes |
| `/audio-blackbox/` | this repo (workflow build) | yes |
| `/bootswatch/` | `bootswatch` (legacy build) | **no** (http allowed) |
| `/drops/` | `drops` (legacy build) | **no** |
| `/eventos/` | `eventos` (legacy build) | **no** |

**Expected after the proxy:** `/`, `/bootswatch/`, `/drops/` and `/eventos/` keep the same content, served from GitHub through Cloudflare. If the zone has "Always Use HTTPS" on, the three http-allowed sites are upgraded to https. GitHub's certificate already covers the host, so they still work over https. Validate each path with the same `curl` checks as in the validation section.

#### (a) + (b) SSL/TLS mode and GitHub's certificate: owner's choice 🔔
**Flexible is never an option.** Cloudflare would talk to GitHub over http. GitHub enforces HTTPS on `/` and redirects back to https, so the browser loops (`ERR_TOO_MANY_REDIRECTS`).

GitHub's certificate for the host is a Let's Encrypt certificate (it expires 2027-01-26 today) and renews about every 90 days. While the host is proxied, GitHub sees Cloudflare IPs and may fail to renew.

| | **Full** (recommended) | **Full (strict)** |
|---|---|---|
| What Cloudflare checks at the origin | TLS is required, but the certificate is not validated (an expired or mismatched one is accepted) | the certificate must be valid for `alexandre.machado.cc` |
| If GitHub's renewal fails | no visible effect: visitors always see Cloudflare's edge certificate, and Cloudflare to GitHub stays encrypted | the paths served by GitHub (`/`, `/bootswatch/`, `/drops/`, `/eventos/`) return **526** once the certificate expires |
| Recurring owner task | **none** | turn the proxy off around each renewal window (~every 90 days; first around 2026-12-27) |
| What you give up | authentication of GitHub's certificate on the Cloudflare-to-GitHub hop (the hop is still encrypted, only an attacker on the path between Cloudflare and GitHub could exploit it) | nothing |

Neither mode affects `/audio-blackbox*`: the Worker serves it from Cloudflare's own storage and never contacts GitHub.

**"Enforce HTTPS" on GitHub (assumed, not verified):** GitHub's documentation says that when it cannot provision the certificate for a custom domain, the "Enforce HTTPS" option becomes unavailable, and enforcement may stop. Under **Full** that is harmless: Cloudflare already redirects visitors to https at the edge ("Always Use HTTPS") and connects to GitHub on 443 regardless. Under **Full (strict)**, an expired or missing GitHub certificate breaks those paths, as described above. Check the `alexandre-machado.github.io` Pages settings after the first renewal window to confirm what actually happens.

**Recommendation: Full**, together with "Always Use HTTPS" on the zone. It avoids the recurring task, and the trade-off (an unauthenticated Cloudflare-to-GitHub hop for the non-Audio-Blackbox sites) is small for static public pages. 🔔 The owner chooses.

#### (d) Who does what
- **Owner:** create the token, set the secret and the variable, choose the SSL mode, flip the proxy (DNS), and handle the renewal window.
- **Team:** code, workflow and runbook only. No Cloudflare resources and no DNS changes.

#### Cutover steps
1. Confirm both deployment jobs succeeded (step 3).
2. In **SSL/TLS > Overview**, set the chosen mode (**Full**, recommended, or **Full (strict)**; never Flexible) and enable **Always Use HTTPS**. These are zone-wide settings. Check the zone's other proxied hosts before changing them.
3. In Cloudflare DNS, keep the CNAME `alexandre.machado.cc -> alexandre-machado.github.io` and change only its proxy status to **Proxied (orange cloud)**.
4. Run section 5 for `/audio-blackbox/` **and** for `/`, `/bootswatch/`, `/drops/` and `/eventos/`.

Only `/audio-blackbox` and its descendants are served by this Worker. The route also matches names such as `/audio-blackbox-other`; the Worker defensively forwards those to the origin. Do not add redirect or WAF rules that would block `/.well-known/acme-challenge/` on this host.

## 5. Validate after cutover

```bash
curl -sI https://alexandre.machado.cc/audio-blackbox/
curl -sI http://alexandre.machado.cc/audio-blackbox/
curl -sI https://alexandre.machado.cc/audio-blackbox
curl -sI https://alexandre.machado.cc/audio-blackbox/pt-br
curl -sI https://alexandre.machado.cc/audio-blackbox/pt-br/
curl -sI https://alexandre.machado.cc/audio-blackbox/release/privacy-policy
curl -sI https://alexandre.machado.cc/audio-blackbox/release/privacy-policy/
curl -sI https://alexandre.machado.cc/audio-blackbox/features/01-capture-and-memory-core.html
curl -sI https://alexandre.machado.cc/audio-blackbox/sitemap.xml
curl -sI https://alexandre.machado.cc/audio-blackbox/llms.txt
curl -sI https://alexandre.machado.cc/audio-blackbox/6e548543f5dfe595b4046686467f6f97.txt
curl -sI https://alexandre.machado.cc/audio-blackbox/assets/icon.png
curl -sI https://alexandre.machado.cc/audio-blackbox/does-not-exist
curl -sI https://alexandre.machado.cc/
curl -s https://alexandre.machado.cc/robots.txt
# Direct origin check: TLS/SNI and Host still use the custom domain.
curl -sI --connect-to alexandre.machado.cc:443:alexandre-machado.github.io:443 https://alexandre.machado.cc/
openssl s_client -connect alexandre-machado.github.io:443 -servername alexandre.machado.cc </dev/null 2>/dev/null | openssl x509 -noout -dates
```

Expect `cf-ray` through the proxy; 200 for canonical pages, the sitemap and text
files; 301 with absolute HTTPS Location for HTTP and noncanonical slash forms;
404 for the missing page. The privacy policy must be 200 without following a
redirect. Feature `.html` URLs must also be 200 without redirecting.
Every Worker response (assets, redirects and 404s) must include
`X-Content-Type-Options: nosniff`, `strict-origin-when-cross-origin`, `X-Frame-Options: DENY` and CSP
`frame-ancestors 'none'`. There is no script-src restriction: GA4, Clarity and
optional Cloudflare Web Analytics remain usable. Images cache for one day and
fonts for seven days; text files use `text/plain`. These rules live directly in
`worker.js`; there is no `_headers` file. HSTS is deliberately absent: it applies
host-wide, including `/bootswatch`, `/drops` and `/eventos`. The owner may enable
HSTS at the zone later after considering those sites and rollback.
The root page should retain the user site's content and GitHub origin headers
(such as `x-github-request-id`); `server: cloudflare`/`cf-ray` alone cannot prove
which origin supplied a page. Compare with the direct-origin response.

## 6. Roll back

Set `alexandre.machado.cc` back to **DNS-only (grey cloud)**. No redeployment is
needed: GitHub Pages still serves the same paths. DNS caches delay universal
rollback until their prior TTL expires (proxied Auto TTL is normally 300 seconds;
some clients cache longer). Optionally remove the Worker route to return traffic
to GitHub even for clients still reaching the proxy. If removing it, disable the
Cloudflare deploy job or clear its token first so the next run cannot recreate it.
GitHub's HTTPS certificate must be valid at rollback. If the owner later enables
zone HSTS, that browser policy persists after rollback. Under **Full**, a lapsed GitHub certificate goes unnoticed while proxied. Before rolling back, check its expiry in the `alexandre-machado.github.io` Pages settings. If it has lapsed, expect HTTPS errors on the GitHub-served paths until GitHub reissues it, which it starts doing once the record is DNS-only. Do not delete the CNAME or change nameservers.

## 7. Keep Pages during observation

Leave both Pages deployments enabled for several stable days and validate all
locales, analytics and the user root site. Disabling this project's Pages later
is a separate owner decision: it removes grey-cloud rollback for this path.
Never disable the user site's Pages while the root still depends on it. If the
project Pages deploy is eventually retired, retain the Jekyll build/artifact and
move the single IndexNow step to follow the remaining successful deployment.

## URL and SEO contract

`html_handling: none` is intentional. Cloudflare's
[automatic HTML handling](https://developers.cloudflare.com/workers/static-assets/routing/advanced/html-handling/)
would strip `.html`, changing existing sitemap URLs. The Worker instead resolves
directory indexes internally and preserves the sitemap's mixed forms: `/`,
`/pt-br/` (likewise es/fr/de/it), `/design/`, feature `*.html`, and extensionless
`/release/privacy-policy`. Asset 307s are converted to absolute 301s.

The sitemap, layout canonical/hreflang links, privacy canonical and both llms
files already use `https://alexandre.machado.cc/audio-blackbox/...`; no edits were
needed. There is no robots file in this repository. The live user-site
`https://alexandre.machado.cc/robots.txt` allows crawling and lists both the root
sitemap and `/audio-blackbox/sitemap.xml` (verified 2026-11-23); it stays outside
the route. The locale, feature and privacy URLs above all returned GitHub 200s
before cutover. `docs/404.html` uses the existing default layout with a neutral
404 label and a link back to the project, `noindex`, and no self-canonical or
`og:url`. Other pages retain their canonical output. This operator runbook stays
published, consistent with the other development docs.

## Why the Cloudflare jobs run on GitHub-hosted runners (do not move them back)

`Site / Test and dry-run Cloudflare` and `Site / Deploy Cloudflare` deliberately run on `ubuntu-latest`, as does `Infrastructure / Validate Cloudflare token plan`. Under #480 (owner AB-2411-47), Android `build`/`custom-listing-diff`, `Site / Build and validate Jekyll`, and `Site / Deploy GitHub Pages` also run on `ubuntu-latest`. Only Android `instrumented-tests` and `deploy-playstore` stay self-hosted. The Cloudflare runner isolation was approved by the infrastructure owner on 2026-11-23 (PR #461, `@sec` SEC-1).

Before #480, the persistent self-hosted runner ran `Site / Build and validate Jekyll` and its ~90 third-party gems as the same user. A compromised gem could tamper with `~/.gitconfig` (which `actions/checkout` copies), with runner binaries, or with another job's environment before the Cloudflare token is used. A fresh GitHub-hosted VM per job removes that path. The repo is public, so hosted minutes cost nothing. The vaelo.club hotsite deploy uses the same arrangement. The artifact handed over from the separate hosted build is additionally refused if it contains anything other than regular files and directories (SEC-8).

## Only one deploy path: GitHub Actions

Deploys must go only through the `Site / Deploy Cloudflare` job (id `deploy-cloudflare`), which is gated by the `cloudflare-hotsite` environment and limited to `main`. **Do not connect Cloudflare Workers Builds (the Git integration) to this repository.** It would add a second deploy path outside that gate, with per-branch preview builds, and its build image cannot run this repo's Jekyll toolchain. If it is connected, disconnect it under Workers & Pages > the Worker > Settings > Builds.
