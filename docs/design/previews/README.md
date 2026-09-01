# Tile icon PR review evidence (issue #268)

These PNGs are review evidence for PR #269, not covered by the CI screenshot-capture gate
in `AGENTS.md` §9 (that gate applies only to
`distribution/metadata/android/{en-US,pt-BR}/images/phoneScreenshots/*.png` and
`docs/assets/screenshot_*.png`, both produced by `ScreenshotCaptureTest`; the QS tile is a
system surface outside that capture).

**Synthetic previews** (`render_ic_tile_logo_preview.py`, an even-odd scanline fill of the
drawable's own path data — built before device access was available):
- `ic_tile_logo_active_24dp_preview.png`
- `ic_tile_logo_inactive_24dp_preview.png`

**Real device evidence**, captured over `adb` on the owner's Samsung SM-S931B (S25),
Android 16, the Tier 2 device in `AGENTS.md` §6 — the `cc.machado.audioblackbox.staging`
debug build installed and its Quick Settings tile pinned and expanded in the real shade:
- `device_s25_qs_shade_context.png` — the tile alongside stock system tiles (Wi-Fi
  toggle, airplane mode, flashlight, brightness/volume sliders) for a true-size legibility
  comparison. Cropped to exclude the Wi-Fi SSID and carrier/notification rows (personal
  data), not retouched otherwise.
- `device_s25_tile_icon_crop.png` — a straight 3x crop of just the tile icon from the same
  screencap.

Both real-device captures show the same visual state: `AudioBlackboxTileService`'s
label/active-vs-inactive rendering did not visibly change across a `click-tile` toggle in
this test session, which tracks with issue #267 (the tile's stop/toggle bug, being fixed in
parallel and out of scope here) rather than anything in this drawable. What the captures do
confirm is the thing this issue is actually about: the mark re-fit to 24dp renders as a
distinct, legible isometric-box silhouette in the real Quick Settings panel, not a blur and
not the old mic glyph.
