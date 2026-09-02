# Documentation

## MVP feature specification

The full feature breakdown, one document per module:

| Module | Document | Tracking |
| --- | --- | --- |
| 1 — Capture & Memory Core | [features/01-capture-and-memory-core.md](features/01-capture-and-memory-core.md) | [#2](https://github.com/alexandre-machado/audio-blackbox/issues/2) |
| 2 — Android Integration & Background Survival | [features/02-android-integration.md](features/02-android-integration.md) | [#3](https://github.com/alexandre-machado/audio-blackbox/issues/3), [#4](https://github.com/alexandre-machado/audio-blackbox/issues/4) |
| 3 — Export Engine | [features/03-export-engine.md](features/03-export-engine.md) | [#5](https://github.com/alexandre-machado/audio-blackbox/issues/5) |
| 4 — User Interface | [features/04-user-interface.md](features/04-user-interface.md) | [#6](https://github.com/alexandre-machado/audio-blackbox/issues/6), [#7](https://github.com/alexandre-machado/audio-blackbox/issues/7) |

## Testing

[testing/tiers.md](testing/tiers.md) — the three test tiers (JVM unit, emulator-based
instrumented, scripted S25 smoke), what each covers, and the single command to run each one.
See also [development/running-on-device.md](development/running-on-device.md) for connecting to
the S25 in the first place.

## Search & AI discoverability

- **Live site:** https://alexandre.machado.cc/audio-blackbox/
- **Sitemap:** https://alexandre.machado.cc/audio-blackbox/sitemap.xml (`docs/sitemap.xml`), linked from
  `docs/index.html` via `<link rel="sitemap">`. Submit this URL directly in Search Console and Bing
  Webmaster Tools — there is no discovery crawl to rely on otherwise.
- **`robots.txt` is intentionally not shipped here.** The custom domain `alexandre.machado.cc` is bound
  to a *different* GitHub Pages site (the account's user Pages site); this repository only serves the
  `/audio-blackbox/` sub-path under it. A `robots.txt` placed at `docs/robots.txt` would resolve to
  `.../audio-blackbox/robots.txt`, which no crawler reads (`robots.txt` is only honoured from the domain
  root). The apex `https://alexandre.machado.cc/robots.txt` currently returns the GitHub Pages 404, which
  crawlers treat as allow-all, so nothing is blocking indexing today. Changing that is outside this
  repo's control and belongs to whoever owns the apex Pages site.
- Search Console / Bing Webmaster verification and backlink work are tracked in the companion
  discoverability issue, not here.

## Decisions

- **Design system** — Material 3 (stable 1.4.0 line) themed with the US
  aviation/cockpit-avionics brand palette. Originally decided as stock-Material-3-only
  in [#9](https://github.com/alexandre-machado/audio-blackbox/issues/9); superseded by
  [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220) once PR
  [#186](https://github.com/alexandre-machado/audio-blackbox/pull/186) shipped the
  avionics theme and the owner ratified it as the system of record.
  [`docs/design/model.html`](design/model.html) is the **spec of record** for this
  theme (its `:root` tokens are canonical); `ui/theme/Color.kt` is the Compose
  implementation of that spec and currently diverges from it on several tokens — see
  `AGENTS.md` §5 for the known gaps.
