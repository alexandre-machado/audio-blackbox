# Cloudflare hotsite operator runbook

The public address remains **https://alexandre.machado.cc/audio-blackbox/**.
The Play Console privacy-policy URL remains
**https://alexandre.machado.cc/audio-blackbox/release/privacy-policy**.
No domain migration or Play Console edit is needed. GitHub Pages remains the
origin and rollback target; the user site's `/` is outside the Worker route.

## 1. Create the deployment token

In Cloudflare, create a custom API token with these dashboard permissions:

| Permission | Resource scope |
| --- | --- |
| Account > Workers Scripts > Edit | Only the account containing machado.cc |
| Zone > Workers Routes > Edit | Include > Specific zone > machado.cc |
| Zone > Zone > Read | Include > Specific zone > machado.cc |

The first two permissions deploy the script/assets and manage its zone route.
The third is needed to resolve the configured `zone_name` through the Zones API;
the two write permissions alone do not grant the documented zone lookup access.
Do not grant DNS Edit, KV, R2, Pages or all-zone access. The account ID is supplied
explicitly, so account discovery is not needed.
The exact legacy dashboard names above are still listed in Cloudflare's
[API token permission reference](https://developers.cloudflare.com/fundamentals/api/reference/permissions/);
the API also calls Edit permissions Write. See the
[zone lookup permission](https://developers.cloudflare.com/api/resources/zones/methods/list/)
and [Worker route authorization](https://developers.cloudflare.com/workers/authorization/workers/).
These were checked for the Wrangler 4 deployment on 2026-11-23. Token authorization
and resource creation still require the owner's first authenticated deployment.

Set an explicit token expiry and record a rotation date before it expires. Workers
Scripts Edit remains account-wide and Routes Edit zone-wide. Optionally restrict
client IPs only if the runner has a stable, known egress IP; a changing egress
would otherwise break deployment.

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

Run **Actions > Deploy GitHub Pages > Run workflow**, selecting **main**, or push
a change under `docs/**` to main. Cloudflare configuration and staging-script
changes also trigger this workflow. Do not dispatch an older branch after cutover.

The build renders Jekyll in production mode, validates SEO/analytics and Liquid,
and uploads the `github-pages` artifact. The job graph is `build → deploy` (Pages)
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
