# Icon design sources

Design **sources and references** for the app icon live here. Shipped assets do not — see "Where the shipped assets go" below.

Tracking issue: **#49** (`decision: app icon concept and asset pipeline`), which carries `@research`'s brief.

## Record provenance for every file you add here

For each source file, add a row below. This is not bookkeeping for its own sake: the licence decides whether the artwork can ship at all, and that question is much harder to answer six months later than today.

| File | Origin | Licence | Commercial use in a published app? | Attribution required? |
|---|---|---|---|---|
| `icon.1.jpg` | AI-generated (Gemini image creation, Google) by the repo owner, 2026-08. Colour/glossy isometric render, "FLIGHT RECORDER" lettering. | No third-party copyright asserted; repo owner is the prompter/generator. Google's Gemini image outputs carry an invisible SynthID watermark. Whether purely AI-generated artwork is eligible for copyright protection at all varies by jurisdiction — noted here as a consideration, not a legal conclusion, and it did not change any artwork decision below. `@research` separately verifying Google's commercial-use terms. | Not adopted as shipped artwork (see "why neither reference ships" below); kept as a reference only. | No — not shipped. |
| `icon.2.jpg` | AI-generated (Gemini image creation, Google) by the repo owner, 2026-08. Pure line-art/outline redraw of `icon.1`, generated against Google's Material Symbols "designing icons" guidance (see the spec-mismatch note below). | Same as `icon.1`. | Not adopted (see below). | No — not shipped. |
| `icon.3.jpg` | AI-generated (Gemini image creation, Google) by the repo owner, 2026-08. Solid filled black silhouette (capsule + upright box + base plate + connector), generated against `@techlead`'s corrected guidance. | Same as `icon.1`. | Traced and heavily pruned into Candidate B's vector geometry (`candidates/candidateB_foreground_not_shipped.xml`). Not shipped as the default icon — see verdict below — but committed as the documented alternative. | No — derived original vector paths, not the source pixels. |
| `ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml` (shipped, in `app/src/main/res/drawable/`) | Drawn in-house by `@design` as plain geometric vector paths (rounded-rect/pill primitives with explicit coordinates), informed by `icon.1`–`icon.3` only as reference for what a flight recorder looks like, not traced or copied. | Original work, no licence question. | Yes. | No. |
| `candidates/candidateB_*_not_shipped.xml` | Drawn in-house by `@design`, same basis, tracing the *composition* of `icon.3` (capsule + box + plate + connector) at a much reduced detail budget, not its pixels. | Original work. | Not shipped (documented alternative). | No. |

**Why neither `icon.1` nor `icon.2` ships, and why originals were drawn instead of tracing them directly**: both fail the technical requirements below regardless of licence (see "Two rejected references" below) — text, gloss, isometric shading and a baked drop shadow don't survive an adaptive icon's foreground/background compositing, and outline-only line art disappears or turns to mush at 48dp. Drawing clean geometric primitives in-house also sidesteps the trade-dress question `@research` raised (a generic "orange band on a capsule" is the shared regulatory concept, not one manufacturer's housing) more cleanly than tracing a specific rendered image would.

**Stray file, not artwork**: `Gemini_Generated_Image_hlwo6nhlwo6nhlwo.jpg:Zone.Identifier` is a WSL/NTFS metadata artifact (25 bytes) left over from copying a file out of Windows, not a reference image. It is not committed; `.gitignore` now has a `*:Zone.Identifier` entry so it can't land in a PR by accident.

## Two specs govern this icon, and they are not interchangeable

The repo owner generated `icon.1`/`icon.2` using guidance from Google's [Material Symbols "designing icons" page](https://m3.material.io/styles/icons/designing-icons) — a 24dp grid, stroke-weight conventions, the fill/weight/grade axes. **That page is correct guidance for the wrong artifact.** It governs Material Symbols / in-app UI icons (line art, thin strokes, small inline glyphs) — it is very likely why `icon.2` came out as thin outline work instead of a filled shape. It is **not** the launcher-icon spec, and none of its stroke-weight or keyline conventions were carried into the artwork below.

The launcher icon here follows the [Android adaptive-icon spec](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) and the [Play Console icon spec](https://developer.android.com/distribute/google-play/resources/icon-design-specifications) instead, as `@research`'s brief on issue #49 cites: 108dp canvas, 72dp visible, ~66dp guaranteed-safe circle, foreground+background+monochrome composition, filled shapes with mass rather than line art, plus a separate 512×512 PNG for the Play Console.

What *does* carry over from the Material Symbols guidance, and was kept: the general principle of a simple, legible silhouette with consistent optical weight. What was left behind: the 24dp grid and its stroke-weight/keyline conventions, which are UI-icon idioms, not launcher-icon ones.

The Material Symbols spec is the *right* one for this app's **in-app** icons — `res/drawable/ic_notification_mic.xml` already exists under that idiom, and issues #7 (gallery) and #46 (engine switch) will need more in the same register. That work is out of scope here; noted so the next person reaching for icon guidance picks the right spec for the artifact they're actually drawing.

Origins that need care:
- **Stock / free icon sites** — several popular ones require attribution, and some restrict use as an app icon or trademark-like use specifically. `@research` confirmed The Noun Project's free tier is CC BY 3.0 (attribution required) and could **not** verify Flaticon's terms at all (their legal pages returned 403). Do not assume "free to download" means "free to ship".
- **AI-generated** — record the tool and the date. Terms vary and change.
- **Commissioned or drawn in-house** — cleanest position, and the one `@research` recommended precisely because it removes every licensing question at once.
- **Anything resembling a specific manufacturer's product**, or any airline / aviation-authority marking, is a trade-dress risk. The generic "orange recorder capsule" concept is a shared regulatory requirement and not proprietary; a literal copy of one company's housing is a different matter.

## Why a JPG cannot ship as an icon

A JPG is fine as a **reference** and useless as a **shipped asset**:

- **No alpha channel.** An icon needs transparency around the artwork; a JPG will carry an opaque rectangle.
- **Lossy compression** produces ringing artefacts exactly at hard edges, which is most of an icon.
- Android wants a **vector** (or clean PNG layers) for the adaptive icon, and Play Console wants a **PNG**.

So a JPG source has to be redrawn or traced into the real assets. Keep the JPG here as the reference it is.

## Where the shipped assets go

`minSdk` is **29**, and adaptive icons are API 26+. So **every supported device uses the adaptive icon** — there is no need for the legacy `mipmap-hdpi/xhdpi/...` PNG ladder.

| Asset | Location |
|---|---|
| Adaptive icon definition | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` |
| Foreground / background layers | `app/src/main/res/drawable/` (vector preferred) |
| Monochrome layer (themed icons, Android 13+) | referenced from the same adaptive-icon XML |
| Play Console store icon (512×512 PNG) | `docs/design/store/` — versioned here, uploaded to the Console by hand |

### Resolved: the placeholder is replaced (issue #49)

`mipmap-anydpi-v26/ic_launcher.xml` used to point `foreground` at `@android:drawable/ic_btn_speak_now` — a **platform** drawable whose appearance varies across OEM skins and Android versions, with no `<monochrome>` layer at all. It now wires three app-owned vector layers (`ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, all in `app/src/main/res/drawable/`), built from Candidate A below.

## Constraints the artwork has to satisfy

- **108dp canvas, 72dp visible, ~66dp guaranteed safe.** Launcher masks (circle, squircle, rounded square) crop differently, so anything outside the safe zone may be cut on some devices.
- **The accent must survive the mask.** Per the repo owner's direction, bright orange is an **accent detail**, not the dominant field — so verify the accent is not the part that gets cropped, or the point of it is lost.
- **The monochrome layer flattens to a single colour.** Whatever the accent colour was doing, it stops doing it there — the silhouette alone has to still read as a flight recorder.
- **Legible at 48dp.** Fine detail (handles, beacons, small text) turns to mud at launcher size.
- Works against **both light and dark** launcher wallpapers.

## Two rejected references, and what a launcher icon needed instead

`icon.1.jpg` (colour, isometric, glossy, "FLIGHT RECORDER" lettering) reads well large and is unusable as an icon: the text is illegible at 48dp, the gloss highlights and outline treatment assume a single flat background that an adaptive icon's separate foreground/background compositing doesn't give you, the drop shadow is baked onto a white field that won't exist at runtime, and the housing is roughly 90% saturated orange against the owner's own "accent, not dominant field" constraint.

`icon.2.jpg` (pure line art, no fill) fixes the text, gloss and shadow, but is the wrong *class* of artwork: an outline-only shape with a white interior either goes transparent (the recorder's body disappears, background shows through the lines) or gets a baked white fill that clashes with any background colour. Its strokes are also sized for a 24dp UI-icon grid (see the spec-mismatch note above), not a 48dp launcher icon — the ventilation grid, bolt-hole ellipses and connector layers vanish or turn to grey mush at that size. Instructive rather than usable: flattened to one colour, it is a tangle of internal lines, not a clean silhouette — direct evidence about the detail budget a launcher icon can afford, not a style to follow.

`icon.3.jpg` (solid filled black silhouette) answers the class problem: filled mass, no text, no gloss, no baked shadow, and it demonstrably reads as a flight recorder even as one flat colour. It still needed work before it could ship as an icon: it sits off-centre with a large empty margin (needs recentring and scaling to fill the 66dp safe zone), and it still carries detail that dies at 48dp — the nine-square ventilation grid, two bolt-hole ellipses, the three-layer nested connector, and especially thin white seam lines on the box faces that are only a few pixels wide even at the reference's full 2048px resolution.

**Explicit decision on the white negative space**: in `icon.3`, white areas do structural work (the capsule's end face, the plate's top surface, seam lines). For the actual vector icon, none of that is reproduced as a *filled* light colour internally — it is dropped entirely (the seam lines) or reduced to a **thin transparent groove** where two masses meet, so the app's background layer shows through. The single case where this groove technique is used deliberately is the orange accent band: it is cut out of the capsule as a hole with a transparent sliver on each side, so the band still reads as a distinct, bounded shape when the whole layer is flattened to one colour for the monochrome/themed state — its presence does not depend on the hue surviving.

**Detail dropped from `icon.3` for Candidate B**, in the order it stopped affecting legibility, from first-cut to last: the ventilation grid, the two bolt-hole ellipses, the three nested connector layers (reduced to one rounded-rect nub), the base-plate's own edge bevel/seam, and all internal white seam lines. What's kept: capsule + upright box + base plate + a single connector nub, plus the new orange band.

## Candidates

Both candidates share the same colour grammar: dominant field is the existing `@color/ic_launcher_background` (`#1B1B1B`), the housing mass is a new neutral `@color/ic_launcher_capsule` (`#E4E4E4`, no hue — needed for contrast against the dark background at 48dp, since a dark-on-dark silhouette would fail the legibility requirement outright), and `@color/ic_launcher_accent` (`#FF5722`) is the only saturated colour anywhere in either drawable.

**Candidate A — "banded capsule" (shipped)**: a single rounded-capsule silhouette (`app/src/main/res/drawable/ic_launcher_foreground.xml`), centered in the safe zone, with the inset orange band cut as a shape (see above). This is the direction `@research`'s brief on issue #49 ranked first.

**Candidate B — the owner's concept, based on `icon.3.jpg`** (`docs/design/icon/candidates/candidateB_foreground_not_shipped.xml`, not wired into the app): capsule + upright box + base plate + one connector nub, pruned as described above, with the same band technique applied to the capsule segment.

### Verdict: Candidate A ships, Candidate B does not — and here is the honest reason why

Both were rendered at 48dp, 72dp and 512px, under circle/squircle/rounded-square masks and as flattened monochrome, into `docs/design/icon/previews/` (see below for the exact method). At 512px and as a large flat silhouette, **B reads clearly** as a flight recorder — `@techlead`'s read of `icon.3` holds up once traced into vector form. But at **48dp, B degrades into an ambiguous blob**: the box, capsule and connector nub are all small enough at that size that they read as an unclear compound shape (closer to a plug or a toaster than a recorder) rather than the three legible parts they are at store-icon size. Candidate A's single pill stays crisp and unambiguous at every size and under every mask tested, including as a flattened monochrome shape.

So: **A survives 48dp; B does not**, and that is the deciding factor for what actually ships as a launcher icon (as opposed to a store listing or in-app illustration, where B's extra specificity might be the better choice — that's a separate, later decision, not made here). Candidate A is wired into `mipmap-anydpi-v26/ic_launcher.xml`. Candidate B's vector layers, and both candidates' previews, are committed for the record and for that possible later reuse.

### How the accent-survives-the-mask and light/dark-wallpaper checks were done

`docs/design/icon/previews/` contains, for each candidate: the foreground composited over the background at 48dp and 72dp under all three mask approximations (circle, squircle, rounded-square — implemented as a circle inscribed at 72dp diameter, a superellipse of the same bounding size, and a rounded square at ~22% corner radius; these are standard reference shapes for evidence, not a guarantee of pixel-identical OEM launcher masks), plus the monochrome layer rendered as a flat tint over both a light and a dark wallpaper stand-in. In every one of those renders the orange band remains visible and unclipped — by construction it sits at most ~18dp from the icon's center in Candidate A and ~17dp in Candidate B, well inside the 33dp safe-zone radius, so no mask in the set crops it. The 512×512 store render and all preview PNGs were generated from the same path data as the shipped vector drawables (via a one-time build script, not hand-drawn separately), so what you see in the previews is the same geometry that ships.

What this checks did **not** cover: an actual Android launcher on a real device or emulator (out of scope for this pass — no device work was done here, per the operating rules), and OEM-specific mask shapes beyond the three standard references.
