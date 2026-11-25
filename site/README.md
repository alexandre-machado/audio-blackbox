# Standalone hotsite generator

Requires Node 20+ and npm. No Ruby/Jekyll or network access is needed during build/test after installation.

```sh
npm --prefix site ci --ignore-scripts
npm --prefix site run build
npm --prefix site test
```

`dist/` is a disposable, ignored asset tree, with the same unprefixed structure as Jekyll's `_site`. The Cloudflare workflow builds and deploys this tree; GitHub Pages retains its separate Jekyll build. Runtime dependencies: none. Pinned build-only dependencies: `yaml@2.9.1` (approved YAML data), `marked@15.0.12` (existing Markdown documentation). Playwright is a pinned development-only dependency for captures. All are locked in `package-lock.json`.

## Sources and templates

- `build.mjs` reads `docs/_data/landing/*.yml`, `docs/_data/languages.yml`, and `_config.yml`. Landing HTML/JSON-LD/interactive scripts are ported to ordinary JavaScript template modules under `templates/`; approved copy remains in YAML.
- `templates/layout.mjs` owns the only site header and footer. All six landings, the bilingual legal pages, 404, design prototype/legacy redirect, and public Markdown pages use this shell. Locale-specific approved labels and destinations vary; the structure stays identical. Legal translations remain EN/PT-BR at their existing shared URLs; no translations or locale-specific legal routes are invented.
- Legal body content and language-toggle scripts are read directly from `docs/release/{privacy-policy,terms}.html`. Existing design content/scripts and public Markdown documents also come directly from `docs/`.
- Landing CSS is extracted unchanged from the existing layout, documentation CSS is copied with front matter removed, and shared tokens come directly from `docs/design/index.html`. Only shell styling is new. Existing production GA4/Clarity configuration is retained.
- Sitemap, LLM files, IndexNow key and image assets are copied directly. Legal files stay `release/*.html` on disk: the unchanged Worker exposes their extensionless canonical URLs. README HTML routes remain absent, matching live 404 responses.

## Inventory and validation

`test/fixtures/live-inventory.json` records a serial curl GET capture: 55 URLs (25 successful responses, 22 redirects, 8 not-found responses), including all 15 sitemap entries, all locale variants, legal variants, 404 behavior, public documents and the legacy design redirect. Each record includes status, Location, canonical, hreflang, title, description, robots, full JSON-LD, types, social metadata and response SHA-256. URLs are percent-encoded before requests. Refresh explicitly with `npm --prefix site run inventory`; review the fixture diff when approved source content or the deployed site changes. A failed capture leaves the prior fixture intact.

`npm --prefix site test` builds first, then runs offline `node --test`. Tests invoke the **actual unchanged Worker** with a filesystem ASSETS adapter modeling `html_handling: none` and `not_found_handling: 404-page`. They compare response status/Location and full metadata, sitemap URLs/alternates, byte-identical source files, unified shell structure, links/assets/fragments, approved legal content, YAML/visible/structured FAQs and analytics loaders. This is not a real Cloudflare deployment test.

Optional non-vacuity check: `node site/scripts/check-mutations.mjs`. It mutates production templates to remove canonical metadata, omit the footer and break an image URL, requires the corresponding failing tests, then restores each source file in `finally` and rebuilds. No Git commands are used.

## Intentional differences

- One responsive, wrapping header/footer replaces the divergent navigation shells; all six language links appear in both. No hamburger interaction is required. The translated Pro-unlock link targets a new `#pricing` anchor at the existing approved download/pricing copy. Footer adds contact and language links. All approved landing navigation/footer text is retained (live comparison on 2026-11-25: zero missing labels in each of six locales). Existing links now open in the same tab; PT-BR terms retain their language anchor.
- The existing broken feature-document link to `app/src/.../Color.kt` now points to GitHub source. Store-listing asset links written relative to the repository root now resolve under the hosted site. Markdown document links use emitted `.html` destinations.
- Markdown is rendered with Marked instead of Kramdown; generated markup/typographic processing can differ. Metadata and source prose remain unchanged. Landing prose outside the disclosed simulator additions, legal main content, sitemap URL set, canonical/hreflang sets, full JSON-LD and OG/Twitter values have no intentional parity exceptions.
- The existing RAM calculator additionally estimates saved AAC/Opus sizes, with approximate-size, Opus-availability and binary-unit notes. These simulator strings remain the previously disclosed copy additions.
- New key in all six locales: `nav_pro`: EN “Pro unlock”; PT-BR “Desbloqueio Pro”; DE “Pro-Freischaltung”; ES “Desbloqueo Pro”; FR “Déblocage Pro”; IT “Sblocco Pro”. There was no existing Pro/pricing heading key to reuse.
- Static copying now omits Jekyll special/backup files, README.md and configured exclusions; `_config.yml` currently has no explicit exclude list.
- Browser visual review is deferred to the requested later impeccable pass; current validation covers generated content and routing, not rendered geometry.

## Cloudflare build and captures

The workflow runs a separate Node build/test job and uploads `site/dist` as
`cloudflare-site`. Both Cloudflare jobs stage it under `.staged/audio-blackbox/`
with a root 404 fallback. Push filters include site/** and the staging script. PRs run only a read-only Node build/test/stage/Wrangler dry-run job without secrets; all existing build/deploy jobs exclude pull_request. Both Cloudflare jobs invoke the tested staging script.
See [the runbook](../docs/development/cloudflare-hotsite.md) for validation and rollback.

Capture five representative pages at both requested viewports:

```sh
npm run build
npx playwright install chromium
node scripts/capture.mjs
```

Set `PLAYWRIGHT_BROWSERS_PATH` for a custom writable browser cache.
Captures go to ignored `site/captures/`; the local server closes after capture.
The saved-file calculator keeps the existing binary MB convention (1,048,576 bytes,
MiB), with a visible unit note. Rates follow the app's AAC and Opus encoders;
estimates exclude container overhead and codec variability.
