# Standalone hotsite generator — Phase 1

Requires Node 20+ and npm. No Ruby/Jekyll or network access is needed during build/test after installation.

```sh
npm --prefix site ci
npm --prefix site run build
npm --prefix site test
```

`dist/` is a disposable, ignored asset tree, with the same unprefixed structure as Jekyll's `_site`. It is **not deployed by this change**. Runtime dependencies: none. Pinned build-only dependencies: `yaml@2.9.1` (approved YAML data), `marked@15.0.12` (existing Markdown documentation). Both are locked in `package-lock.json`.

## Sources and templates

- `build.mjs` reads `docs/_data/landing/*.yml`, `docs/_data/languages.yml`, and `_config.yml`. Landing HTML/JSON-LD/interactive scripts are ported to ordinary JavaScript template modules under `templates/`; approved copy remains in YAML.
- `templates/layout.mjs` owns the only site header and footer. All six landings, the bilingual legal pages, 404, design prototype/legacy redirect, and public Markdown pages use this shell. Locale-specific approved labels and destinations vary; the structure stays identical. Legal translations remain EN/PT-BR at their existing shared URLs; no translations or locale-specific legal routes are invented.
- Legal body content and language-toggle scripts are read directly from `docs/release/{privacy-policy,terms}.html`. Existing design content/scripts and public Markdown documents also come directly from `docs/`.
- Landing CSS is extracted unchanged from the existing layout, documentation CSS is copied with front matter removed, and shared tokens come directly from `docs/design/index.html`. Only shell styling is new. Existing production GA4/Clarity configuration is retained.
- Sitemap, LLM files, IndexNow key and image assets are copied directly. Legal files stay `release/*.html` on disk: the unchanged Worker exposes their extensionless canonical URLs. README HTML routes remain absent, matching live 404 responses.

## Inventory and validation

`test/fixtures/live-inventory.json` records a serial curl GET capture: 55 URLs (25 successful responses, 22 redirects, 8 not-found responses), including all 15 sitemap entries, all locale variants, legal variants, 404 behavior, public documents and the legacy design redirect. Each record includes status, Location, canonical, hreflang, title, description, full JSON-LD, types, social metadata and response SHA-256. URLs are percent-encoded before requests. Refresh explicitly with `npm --prefix site run inventory`; review the fixture diff when approved source content or the deployed site changes. A failed capture leaves the prior fixture intact.

`npm --prefix site test` builds first, then runs offline `node --test`. Tests invoke the **actual unchanged Worker** with a filesystem ASSETS adapter modeling `html_handling: none` and `not_found_handling: 404-page`. They compare response status/Location and full metadata, sitemap URLs/alternates, byte-identical source files, unified shell structure, links/assets/fragments, approved legal content, YAML/visible/structured FAQs and analytics loaders. This is not a real Cloudflare deployment test.

Optional non-vacuity check: `node site/scripts/check-mutations.mjs`. It mutates production templates to remove canonical metadata, omit the footer and break an image URL, requires the corresponding failing tests, then restores each source file in `finally` and rebuilds. No Git commands are used.

## Intentional differences

- One responsive, wrapping header/footer replaces the divergent navigation shells; all six language links appear in both. No hamburger interaction is required. The universal `Pro` link targets a new `#pricing` anchor at the existing approved download/pricing copy. Footer adds contact and language links.
- The existing broken feature-document link to `app/src/.../Color.kt` now points to GitHub source. Store-listing asset links written relative to the repository root now resolve under the hosted site. Markdown document links use emitted `.html` destinations.
- Markdown is rendered with Marked instead of Kramdown; generated markup/typographic processing can differ. Metadata and source prose remain unchanged. Landing main content, legal main content, sitemap URL set, canonical/hreflang sets, full JSON-LD and OG/Twitter values have no intentional parity exceptions.
- Browser visual review is deferred to the requested later impeccable pass; current validation covers generated content and routing, not rendered geometry.

## Phase 2

1. Change the site workflow to install Node/build dependencies with `npm ci`, build/test `site/`, and upload its `dist/` artifact. Preserve workflow permissions, runner choices and deployment guards.
2. Adapt/replace the old Jekyll validation script: it currently requires exactly six language anchors, whereas the unified header **and** footer now produce twelve. Carry its checks into the Node gate, without a Ruby runtime.
3. Point Worker asset staging at the new artifact. Preserve the `/audio-blackbox/` prefix in the staged tree and the root 404 fallback; changing Wrangler directly to unprefixed `site/dist` would break the current Worker. Keep `html_handling: none`, the ASSETS binding and routing semantics.
4. Run Wrangler dry-run/preview verification with the real assets binding, then repeat status/redirect/metadata checks after cutover. Keep a rollback artifact and retain IndexNow submission. Decide the GitHub Pages fallback alongside the workflow switch.

Phase 1 leaves `docs/`, `.github/workflows/`, `cloudflare/worker.js`, and all deployment configuration unchanged.
