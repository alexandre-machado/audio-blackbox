# Icon design sources

Design **sources and references** for the app icon live here. Shipped assets do not — see "Where the shipped assets go" below.

Tracking issue: **#49** (`decision: app icon concept and asset pipeline`), which carries `@research`'s brief.

## Record provenance for every file you add here

For each source file, add a row below. This is not bookkeeping for its own sake: the licence decides whether the artwork can ship at all, and that question is much harder to answer six months later than today.

| File | Origin | Licence | Commercial use in a published app? | Attribution required? |
|---|---|---|---|---|
| `icon.1.jpg` | AI-generated (Gemini image creation, Google) by the repo owner, 2026-08. Colour/glossy isometric render, "FLIGHT RECORDER" lettering. | No third-party copyright asserted; repo owner is the prompter/generator. Google's Gemini image outputs carry an invisible SynthID watermark. Whether purely AI-generated artwork is eligible for copyright protection at all varies by jurisdiction — noted here as a consideration, not a legal conclusion. **Verification was completed** — `@research`'s brief on issue #75, posted 2026-08-24 02:23Z (https://github.com/alexandre-machado/audio-blackbox/issues/75#issuecomment-5389991907), checked Google's commercial-use terms directly. Conclusion: no blocker found in Google's terms for commercial use. Genuinely open items, not resolved by that brief: (a) Google gives no indemnification for Gemini output; (b) under US Copyright Office 2023 guidance the artwork is likely not copyright-protectable, giving weaker trade-dress enforcement against a copycat than the in-house vector it replaced would have had; (c) Brazilian law's treatment of AI-generated works was not verified, which matters because the repo owner is in Brazil. The owner reviewed these open items and accepted the risk knowingly (https://github.com/alexandre-machado/audio-blackbox/pull/76#issuecomment-5395728424) rather than being left with an unresolved question. | **Ships as the default adaptive-icon foreground** (issue #75, reversing the original rejection below — see "Issue #75: icon.1 ships after all" for why). | Not established as a general Google requirement (see `@research`'s brief) — no attribution language was found in Google's terms for this use case. |
| `icon.2.jpg` | AI-generated (Gemini image creation, Google) by the repo owner, 2026-08. Pure line-art/outline redraw of `icon.1`, generated against Google's Material Symbols "designing icons" guidance (see the spec-mismatch note below). | Same as `icon.1`. | Not adopted (see below). | No — not shipped. |
| `icon.3.jpg` | AI-generated (Gemini image creation, Google) by the repo owner, 2026-08. Solid filled black silhouette (capsule + upright box + base plate + connector), generated against `@techlead`'s corrected guidance. | Same as `icon.1`. | No longer ships as the foreground (Candidate B was replaced by `icon.1.jpg`, issue #75). **Still in service** as the source silhouette for the current `ic_launcher_monochrome.xml` — see "Issue #75" below for how it was traced. | No — derived original vector paths, not the source pixels. |
| `ic_launcher_background.xml` (shipped, in `app/src/main/res/drawable/`) | Drawn in-house by `@design`: a flat single-colour fill. | Original work, no licence question. | Yes. | No. |
| `ic_launcher_monochrome.xml` (shipped, in `app/src/main/res/drawable/`) | Drawn in-house by `@design` as plain geometric vector paths (rounded-rect/capsule primitives with explicit coordinates), hand-authored against `icon.3.jpg`'s silhouette and composition (box + cylinder + connector + base plate), not traced pixel-for-pixel. Rebuilt for issue #75 to match `icon.1.jpg`'s pose instead of the old Candidate B layout — see below. | Original work, no licence question. | Yes. | No. |
| `ic_launcher_foreground.xml` (Candidate B, **no longer shipped**, kept in git history only as of issue #75) | Drawn in-house by `@design` as plain geometric vector paths, informed by `icon.1`–`icon.3` only as reference, not traced or copied. | Original work, no licence question. | No — superseded by the `icon.1.jpg`-derived raster foreground below. | No. |
| `app/src/main/res/drawable-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png` (shipped, issue #75) | Derived from `icon.1.jpg` by `@design`/`@techlead`: background keyed out by border flood fill (not a colour key — see "Issue #75" below for why), scaled to 58dp of the 108dp canvas, centred. Pixel content is a re-encoded, background-stripped version of `icon.1.jpg`'s own pixels, not an independent redrawing. | Same licence position as `icon.1.jpg` above (this is derived directly from its pixels) — verification completed, risk accepted, see that row. | **Ships** as the default adaptive-icon foreground (issue #75). | Not established — same as `icon.1.jpg`. |
| `candidates/candidateA_*_not_shipped.xml` | Drawn in-house by `@design`, same basis: a single rounded-capsule silhouette with an inset orange band, no reference to `icon.3`'s composition. Kept as the documented fallback (see verdict below), not wired into the app. | Original work. | Not shipped (documented alternative). | No. |

**Why `icon.2` still does not ship, and why `icon.1` originally didn't either (superseded, see "Issue #75" below)**: this reasoning is preserved because it was correct at the time and the underlying trade-offs are still real, even though the repo owner later decided the trade reads better than a from-scratch redraw for `icon.1` specifically. `icon.2` (outline-only line art) still fails outright — it disappears or turns to mush at 48dp, independent of any owner preference. `icon.1`'s original rejection (text, gloss, isometric shading and a baked drop shadow don't survive an adaptive icon's foreground/background compositing) is detailed in full in "Two rejected references" below and none of those specific technical problems disappeared; issue #75 shipped `icon.1` anyway, on the owner's explicit product-identity call, having seen it running on their own device. Drawing clean geometric primitives in-house (Candidate B, no longer shipped) had sidestepped the trade-dress question `@research` raised (a generic "orange band on a capsule" is the shared regulatory concept, not one manufacturer's housing) more cleanly than tracing a specific rendered image would — that trade-dress consideration did not go away with issue #75, it was simply outweighed by the owner's preference; see below.

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
| Foreground layer | `app/src/main/res/drawable-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png` (raster, since issue #75 — see below for why this is no longer a vector) |
| Background layer | `app/src/main/res/drawable/ic_launcher_background.xml` (vector) |
| Monochrome layer (themed icons, Android 13+) | `app/src/main/res/drawable/ic_launcher_monochrome.xml` (vector), referenced from the same adaptive-icon XML |
| Play Console store icon (512×512 PNG) | `docs/design/store/` — versioned here, uploaded to the Console by hand |

### Resolved: the placeholder is replaced (issue #49)

`mipmap-anydpi-v26/ic_launcher.xml` used to point `foreground` at `@android:drawable/ic_btn_speak_now` — a **platform** drawable whose appearance varies across OEM skins and Android versions, with no `<monochrome>` layer at all. It now wires three app-owned vector layers (`ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, all in `app/src/main/res/drawable/`), built from Candidate B below.

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

**Candidate A — "banded capsule" (not shipped, kept as the fallback)**: a single rounded-capsule silhouette (`docs/design/icon/candidates/candidateA_foreground_not_shipped.xml`), centered in the safe zone, with the inset orange band cut as a shape (see above). This is the direction `@research`'s brief on issue #49 ranked first, and it was the initially-shipped candidate through the first round of this PR.

**Candidate B — the owner's concept, based on `icon.3.jpg` (shipped)**: `app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`. Capsule + upright box + base plate + one connector nub, pruned as described above, with the same band technique applied to the capsule segment.

### Verdict: Candidate B ships — reversed from this PR's first round, on the repo owner's call

**Superseded by issue #75, see the section at the end of this document.** Everything below this point is preserved as the historical record of how Candidate B was chosen over Candidate A; it is no longer the current shipped state. As of issue #75 the shipped foreground is a raster PNG derived from `icon.1.jpg`, not Candidate B's vector geometry — Candidate B is kept in git history and its previews below remain as documented prior art, the same way Candidate A was kept after this round.

Both were rendered at 48dp, 72dp and 512px, under circle/squircle/rounded-square masks and as flattened monochrome, into `docs/design/icon/previews/` (see below for the exact method). My first-round read of those renders was that Candidate A was the safer choice: it stays crisp at every size, and I judged B's 48dp render (`docs/design/icon/previews/candidateB_shipped_48dp_circle.png` and the `squircle`/`rounded-square` variants) as degrading into an ambiguous blob at that size.

`@techlead` and the repo owner reviewed the same committed renders and reached the opposite conclusion, and it is the owner's product-identity call to make: **A is legible but generic** — at both 48dp and 72dp it reads as a capsule, a battery, or a toggle switch, not specifically as a flight recorder. **B still reads as the intended subject at 48dp** — the cylinder, housing and base plate stay distinguishable and the orange band still lands — which for an identity mark outweighs A's cleaner abstraction. For a launcher icon whose entire job is to *be recognized as this app*, reading as the right thing beats reading as a merely-legible thing. Candidate B ships; Candidate A is kept as the documented fallback (its geometry survives 48dp slightly more cleanly, if a purer-abstraction direction is ever wanted again).

Two further compositions were tried and rejected before landing on the as-drawn Candidate B, reported here so the choice reads as arrived-at rather than assumed:
- **Fusing B's four parts into one continuous outline** produced a lumpy, organic contour that read as a cloud or blob — it lost the mechanical, instrument-like character that makes the shape read as hardware at all.
- **Scaling the whole composition up ~6–10% about the icon center to fill more of the 72dp visible area** was tried explicitly to grow the artwork's share of the frame. At that scale the box's top-right corner and the base plate's right edge push past the 72dp visible boundary far enough to be visibly chopped by the circle mask — a flat clipped chord across a corner that reads as accidental mask damage, not intentional design (see `candidateB_shipped_48dp_circle.png` versus the scale-trial renders, not committed, that showed the flat cut). Opening gaps between the parts to compensate was tried too and made things worse — three detached shapes (a circle, a pill, a bar) rather than one machine. **Scale was left unchanged**: growing the artwork cost more than it gained, and shipping B as originally drawn was explicitly acceptable per the brief for this change; a worse B was not.

### How the accent-survives-the-mask and light/dark-wallpaper checks were done

`docs/design/icon/previews/` contains, for each candidate: the foreground composited over the background at 48dp and 72dp under all three mask approximations (circle, squircle, rounded-square — implemented as a circle inscribed at 72dp diameter, a superellipse of the same bounding size, and a rounded square at ~22% corner radius; these are standard reference shapes for evidence, not a guarantee of pixel-identical OEM launcher masks), plus the monochrome layer rendered as a flat tint over both a light and a dark wallpaper stand-in. Filenames now say which is which: `candidateA_not_shipped_*.png` and `candidateB_shipped_*.png`. In every one of those renders the orange band remains visible and unclipped — by construction it sits at most ~18dp from the icon's center in Candidate A and ~17dp in Candidate B, well inside the 33dp safe-zone radius, so no mask in the set crops it. The 512×512 store render (`docs/design/store/ic_launcher_store_512.png`, Candidate B; `ic_store_candidateA_not_shipped_512.png` alongside it) and all preview PNGs were generated from the same path data as the shipped vector drawables (via a one-time build script, not hand-drawn separately), so what you see in the previews is the same geometry that ships.

What this checks did **not** cover: an actual Android launcher on a real device or emulator (out of scope for this pass — no device work was done here, per the operating rules), and OEM-specific mask shapes beyond the three standard references.

## Issue #75: `icon.1` ships after all

The repo owner saw `icon.1.jpg` running on their own S25 (via an experimental branch, `experiment/icon-1-jpg`, built by `@techlead` for that one purpose) and asked for it to be adopted as the shipped launcher icon, reversing the "Two rejected references" decision above. The technical objections recorded above are all still true — none of them stopped being true — the owner simply decided that reading as the actual product photo outweighs them. That is a product-identity call the owner is entitled to make, and it is recorded here rather than silently overwriting the earlier reasoning, so a future reader sees both the original "why not" and the later "why anyway" instead of a document that looks like the rejection never happened.

### What ships now

- **Foreground**: `app/src/main/res/drawable-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png`, five density-specific PNGs derived directly from `icon.1.jpg`'s own pixels (not redrawn). Background keyed out by **border flood fill**, started from the four image edges and flooding through near-white pixels only, stopping at the artwork's dark brown outline. A plain colour key (discarding every near-white pixel regardless of position) was tried first and rejected: it punches holes in the interior gloss highlights on the cylinder and box, and in the light grille panel on the box's front face, because those are near-white too. Flood fill from the border doesn't touch them, since they're not connected to the background through a path of near-white pixels. **Correction (PR #76 review)**: measuring the actual non-transparent pixel bounding box in `ic_launcher_foreground.png` (xxxhdpi, 432×432px = 108dp) against the canvas center gives a farthest content reach of ~34.4dp radius (~68.8dp diameter), not the "58dp, comfortably inside the ~66dp safe zone" originally claimed here — that 58dp figure was the axis-aligned bounding-box width, not the true radial reach to the farthest corner. The actual reach sits outside the 33dp guaranteed-safe radius (66dp safe circle) by roughly 1.4dp, but stays inside the 36dp absolute limit (72dp guaranteed circle) by roughly 1.6dp, so it is not clipped under a circular/squircle themed mask, just close to the edge of the guarantee rather than "comfortable". Left as shipped for this PR; worth a small rescale in a follow-up if more margin is wanted.
- **Background**: `ic_launcher_background.xml`, a flat fill using `@color/ic_launcher_background`, now `#FFFFFF` to match the artwork's own white so the foreground's square edge is invisible against it (a mismatched background would show as a visible seam around the foreground's bounding box). The old `ic_launcher_capsule`/`ic_launcher_accent` colours from Candidate B are removed from `colors.xml` since nothing references them anymore.
- **Monochrome**: rebuilt — see below, this was the blocking gap the issue opened on.
- **Play Store icon**: `docs/design/store/ic_launcher_store_512.png` regenerated from `icon.1.jpg` with the same flood-fill-and-scale technique, at 512×512. Verified directly: PNG, RGBA (8-bit per channel, alpha channel present), matching the corrected spec in `docs/release/play-store.md` (512×512, 32-bit PNG with alpha — that document already carries its own correction note about a prior version having this transposed with the feature graphic's "no alpha" requirement; re-checked against its cited source, `https://support.google.com/googleplay/android-developer/answer/9866151`, and it is accurate as written). `docs/design/store/ic_store_candidateA_not_shipped_512.png` is untouched and still marked not shipped.

### The monochrome layer: why it couldn't be tinted, and how it was actually built

Before this issue, `ic_launcher_monochrome.xml` was still Candidate B's geometry (capsule + upright box + base plate + connector, in Candidate B's own layout) while the foreground became a completely different photorealistic illustration — so Android 13+ themed icons showed a visibly different design from the default icon. A monochrome layer can't be produced by tinting a glossy, shaded illustration; the system needs an actual filled silhouette, and VectorDrawable can't approximate one via filters (no blend modes, no gradients-as-shading, no per-group opacity — solid fills only).

`icon.3.jpg` is already a solid filled silhouette of this same object (capsule/cylinder + upright box + base plate + connector, the same composition `icon.1.jpg` uses), so it's the natural source rather than trying to derive a silhouette from `icon.1.jpg`'s own gloss and colour.

Two approaches were tried:

1. **Automated pixel tracing.** `icon.3.jpg` was thresholded to a binary silhouette, downsampled with box-filtered averaging to suppress sub-detail (the ventilation grid, bolt-hole ellipses, nested connector rings — all well below the legibility floor at 48dp, same conclusion the original Candidate B write-up above reached by hand), then traced into a boundary polygon (a from-scratch boundary-tracing implementation, since no headless SVG/vector tool was available in this environment — Vector Asset Studio is GUI-only and there's no maintained CLI equivalent). This produced an accurate outer silhouette, but flattening the whole object to a single fused outline reproduced the exact "cloud/blob" failure the Candidate B write-up above already documented for a fused Candidate B: at 48dp it read as one soft rounded mass with no mechanical character, not as a box-plus-cylinder instrument.
2. **Hand-authored primitives matching `icon.3`'s layout (what ships).** Four separate solid shapes — base plate, upright box, horizontal cylinder, connector nub — sized and positioned from `icon.3.jpg`'s own proportions, with a small real gap left between the touching masses (not an `evenOdd` cut) so they stay legibly distinct once flattened to one colour. This is the same technique the previous monochrome used for its accent-band groove, just applied as plain separation instead of a hole. All paths are `#FF000000`; the system supplies its own wallpaper-derived tint, so only the alpha/shape matters.

Rendered at 48dp under a circle mask, the result reads clearly as the four-part object (base, box, cylinder, connector). This is a simplified, hand-authored approximation of `icon.1`'s pose, not a pixel-perfect trace of it — matching the discipline the original Candidate B monochrome already followed (solid black paths only, shapes that stay distinct when flattened), just re-targeted at the new foreground's composition instead of Candidate B's.

**Correction (PR #76 review): the first cut of this geometry was cropped, not "nothing cropped" as originally written here.** Measuring each shape's farthest point from the 108dp canvas's center (54,54) against the guaranteed-safe 33dp radius (66dp safe circle) and the absolute 36dp limit (72dp guaranteed circle):

| Shape | Farthest reach, before | Farthest reach, after |
|---|---|---|
| Base plate | 43.05dp | 31.86dp |
| Upright box | 41.35dp | 30.60dp |
| Connector nub | 41.84dp | 30.96dp |
| Horizontal cylinder | 22.67dp (already safe) | 16.77dp |

Three of the four shapes exceeded even the 36dp absolute limit, not just the 33dp guaranteed-safe radius — they would visibly clip under a circular or squircle themed-icon mask on Android 13+. The fix is a uniform 0.74 scale of all four shapes about the (54,54) center, which keeps the isometric proportions intact (same relative sizes and positions, so the mark is still recognisable as the same object, not shrunk to a dot) while bringing the farthest reach to 31.86dp — inside the 33dp safe radius, filling roughly 96% of the safe circle's diameter. See `app/src/main/res/drawable/ic_launcher_monochrome.xml`'s header comment for the exact coordinates.

### Preview renders (not shipped)

Three variants of the shipped foreground artwork were rendered at a realistic 48dp under both circle and squircle masks, into `docs/design/icon/previews/` (not referenced by any shipped resource):

- `icon1_variantA_asis_48dp_{circle,squircle}.png` — the artwork exactly as it ships.
- `icon1_variantB_no_lettering_48dp_{circle,squircle}.png` — "FLIGHT RECORDER" painted over with the surrounding cylinder-surface colour.
- `icon1_variantC_no_lettering_no_shadow_48dp_{circle,squircle}.png` — lettering removed as above, plus the darker contact-shadow band where the cylinder meets the base plate lightened toward the surrounding mid-orange (the *internal* baked shadow within the object's own silhouette, not the external ground shadow beneath the whole object, which falls almost entirely outside the object's own silhouette and is a separate concern from this comparison).

Both (b) and (c) were produced by masking and colour-patching the source JPEG (sampled-colour fill with a softened mask edge, not a proper paint-out/inpaint), since no image-editing tool beyond PIL was available in this environment — treat them as legibility comparisons, not production-quality retouching.

At 48dp, the lettering is not clearly legible in *any* of the three variants, including (a) as shipped — consistent with the original "Two rejected references" finding above that it's an unreadable smudge at launcher size. The internal shadow band is a small, subtle effect at this size in all three. Given that, (b) and (c) are very likely the better choice long-term (removing an illegible label and a shadow that will visually fight Android's own elevation shadow once the icon actually casts one), but the visual difference at 48dp is genuinely small in these renders, and this is exactly the kind of aesthetic call the brief for this issue says the owner should make, not `@design`. **Recommendation, not a decision**: (b) if the owner wants one incremental cleanup with the least risk of the patch looking obviously edited; otherwise ship (a) as directed and revisit if the lettering ever needs to read at a larger size (e.g. the Play Store listing, where it's still legible).
