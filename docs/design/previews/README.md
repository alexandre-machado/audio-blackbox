# Tile icon PR review evidence (issue #268)

These two PNGs are a **local raster preview** of `app/src/main/res/drawable/ic_tile_logo.xml`,
rendered by `render_ic_tile_logo_preview.py` (even-odd scanline fill of the drawable's own
path data, no external rasterizer available in the authoring environment). They are review
evidence for PR #269, not device screenshots and not covered by the CI screenshot-capture
gate in `AGENTS.md` §9 (that gate applies only to
`distribution/metadata/android/{en-US,pt-BR}/images/phoneScreenshots/*.png` and
`docs/assets/screenshot_*.png`, both produced by `ScreenshotCaptureTest`; the QS tile is a
system surface outside that capture).

- `ic_tile_logo_active_24dp_preview.png` — active (tinted-on) tile treatment, white glyph.
- `ic_tile_logo_inactive_24dp_preview.png` — inactive (dimmed-off) tile treatment, dimmed
  gray glyph.

The real Tier 2 check — the tile reading as the logo at true size in the real Quick
Settings shade, in both states — is owner-gated on the S25 (no local emulator available
here).
