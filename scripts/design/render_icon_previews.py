#!/usr/bin/env python3
"""Render committed launcher-icon evidence PNGs from the actual VectorDrawable XML.

Regenerates everything under docs/design/icon/previews/ and the two 512x512
store PNGs under docs/design/store/, by parsing the same VectorDrawable path
data that ships (or, for the not-shipped candidates, that is kept as
documented prior art) and rasterising it with cairosvg + Pillow — the only
two imaging libraries available in this environment (issue #56, issue #60).

Why this file exists now and didn't before (issue #60)
--------------------------------------------------------
The previous renders (PR #58) were produced by a one-off script that was
never committed. That script (or its author, manually) built SVG <path>
elements from VectorDraward pathData without carrying across
`android:fillType="evenOdd"` as `fill-rule="evenodd"` on the SVG side. SVG's
default fill-rule is `nonzero`, so a path with two subpaths that are meant to
punch a hole in each other (the accent-band groove, see
docs/design/icon/README.md's "Explicit decision on the white negative
space") instead unioned solid, and the transparent groove around the accent
band silently disappeared from every rendered preview and from the
Candidate A store PNG. `@rev` caught this on PR #58 by sampling pixels
directly and cross-checking with an independent cairosvg reconstruction that
did carry the fill-rule across correctly.

The fix is entirely in this rendering pipeline: `_vectordrawable_path_to_svg`
below reads `android:fillType` off each <path> and maps it onto SVG's
`fill-rule` (`evenOdd` -> `evenodd`, the VectorDrawable default `nonZero` /
absent -> `nonzero`). No shipped VectorDrawable XML changes as part of this
fix — confirmed by walking every path in every affected drawable and finding
the geometry (M/L/A commands, hole cutout, accent overlay) already matches
what `@rev`'s independent SVG reconstruction rendered correctly.

Geometry note
-------------
This script is a from-scratch, now-committed replacement for the lost
one-off script, not a byte-identical reproduction of it. Pixel dimensions of
the regenerated PNGs are chosen to match the previous convention (384px for
"48dp" renders, 576px for "72dp" renders, 512px for the store icon) but the
exact mask pixel boundaries are computed directly from the documented spec
in docs/design/icon/README.md (72dp-diameter circle / squircle / rounded
square inscribed in the 108dp canvas, ~22% corner radius) rather than
inherited from whatever the previous, unrecoverable script happened to do.
The mask shapes remain **approximations of the three standard reference
launcher-mask shapes**, not pixel-identical OEM masks — see the README's
existing caveat, which this script does not change.

Regression check
-----------------
`assert_groove_present()` renders the affected drawables independently of
the "pretty" preview PNGs (same SVG conversion, but sampling raw pixels
against expected colours) and fails loudly (non-zero exit via
AssertionError) if the accent-band groove's transparent sliver is not
present. This is the pixel-level check the issue asks to be added to the
render script itself, run automatically as part of `main()`.
"""
from __future__ import annotations

import io
import math
import xml.etree.ElementTree as ET
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

ANDROID_NS = "http://schemas.android.com/apk/res/android"

REPO_ROOT = Path(__file__).resolve().parents[2]
CANDIDATES_DIR = REPO_ROOT / "docs" / "design" / "icon" / "candidates"
PREVIEWS_DIR = REPO_ROOT / "docs" / "design" / "icon" / "previews"
STORE_DIR = REPO_ROOT / "docs" / "design" / "store"

# Historical Candidate A/B colour grammar (docs/design/icon/README.md,
# "Both candidates share the same colour grammar"). These candidates are
# kept as documented prior art / not-shipped fallback; regenerating their
# evidence must not change the design, so these are fixed constants rather
# than read from the current app/src/main/res/values/colors.xml (which no
# longer even defines the capsule/accent colours as of issue #75).
CANDIDATE_BACKGROUND = "#1B1B1B"
CANDIDATE_CAPSULE = "#E4E4E4"
CANDIDATE_ACCENT = "#FF5722"

# Wallpaper stand-ins for the flattened monochrome/themed-icon preview, and
# the tint the system would pick for contrast against each (matches the
# values already committed in PR #58's monochrome previews).
DARK_WALLPAPER = "#141414"
LIGHT_WALLPAPER = "#EBEBEB"

CANVAS_DP = 108.0
MASK_DIAMETER_DP = 72.0
ROUNDED_SQUARE_CORNER_FRACTION = 0.22  # ~22% of the mask bounding box side
SUPERSAMPLE = 4


def _hex_to_rgb_and_alpha(value: str) -> tuple[str, float]:
    """Parse a VectorDrawable colour literal (#RRGGBB or #AARRGGBB)."""
    value = value.strip()
    if not value.startswith("#"):
        raise ValueError(f"unsupported colour literal: {value!r}")
    hexpart = value[1:]
    if len(hexpart) == 6:
        return f"#{hexpart}", 1.0
    if len(hexpart) == 8:
        a = int(hexpart[0:2], 16) / 255.0
        return f"#{hexpart[2:]}", a
    raise ValueError(f"unsupported colour literal: {value!r}")


def _resolve_fill_color(raw: str, color_map: dict[str, str]) -> tuple[str, float]:
    if raw.startswith("@color/"):
        name = raw[len("@color/") :]
        try:
            resolved = color_map[name]
        except KeyError as exc:
            raise ValueError(f"no colour mapping supplied for @color/{name}") from exc
        return _hex_to_rgb_and_alpha(resolved)
    return _hex_to_rgb_and_alpha(raw)


def _vectordrawable_path_to_svg(vector_xml_path: Path, color_map: dict[str, str]) -> str:
    """Convert a VectorDrawable XML file to an equivalent standalone SVG.

    This is the fix for issue #60: every <path>'s `android:fillType` is
    mapped onto the SVG `fill-rule` attribute explicitly. VectorDrawable's
    default fill type is `nonZero` (SVG's default is also `nonzero`, so
    omitting it is safe only for paths that don't set fillType at all);
    `evenOdd` MUST be carried across as `fill-rule="evenodd"` or a
    hole-cutout path silently unions solid instead of punching through.
    """
    tree = ET.parse(vector_xml_path)
    root = tree.getroot()
    viewport_w = float(root.attrib["{%s}viewportWidth" % ANDROID_NS])
    viewport_h = float(root.attrib["{%s}viewportHeight" % ANDROID_NS])

    svg_paths = []
    for path_el in root.iter("path"):
        fill_color_raw = path_el.attrib.get("{%s}fillColor" % ANDROID_NS)
        if not fill_color_raw:
            continue  # stroke-only paths, not used by this icon
        path_data = path_el.attrib["{%s}pathData" % ANDROID_NS]
        fill_type = path_el.attrib.get("{%s}fillType" % ANDROID_NS, "nonZero")
        fill_alpha_attr = path_el.attrib.get("{%s}fillAlpha" % ANDROID_NS)

        rgb_hex, alpha_from_argb = _resolve_fill_color(fill_color_raw, color_map)
        alpha = float(fill_alpha_attr) if fill_alpha_attr is not None else alpha_from_argb

        # THE FIX: evenOdd must survive the VectorDrawable -> SVG translation.
        svg_fill_rule = "evenodd" if fill_type == "evenOdd" else "nonzero"

        svg_paths.append(
            f'<path d="{path_data}" fill="{rgb_hex}" fill-opacity="{alpha}" '
            f'fill-rule="{svg_fill_rule}"/>'
        )

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'viewBox="0 0 {viewport_w} {viewport_h}">'
        + "".join(svg_paths)
        + "</svg>"
    )


def _render_vector_layer(vector_xml_path: Path, color_map: dict[str, str], px: int) -> Image.Image:
    svg = _vectordrawable_path_to_svg(vector_xml_path, color_map)
    png_bytes = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png_bytes)).convert("RGBA")


def _flat_layer(hex_color: str, px: int) -> Image.Image:
    rgb, _ = _hex_to_rgb_and_alpha(hex_color)
    r, g, b = Image.new("RGB", (1, 1), rgb).getpixel((0, 0))
    return Image.new("RGBA", (px, px), (r, g, b, 255))


def _mask(shape: str, px: int) -> Image.Image:
    """Build an anti-aliased alpha mask for one of the three reference shapes.

    Rendered at SUPERSAMPLE x resolution then downsampled, matching the
    treatment cairosvg gives the vector layers so mask edges and artwork
    edges anti-alias comparably.
    """
    ss_px = px * SUPERSAMPLE
    scale = ss_px / CANVAS_DP
    center = CANVAS_DP / 2.0 * scale
    radius = (MASK_DIAMETER_DP / 2.0) * scale

    mask_img = Image.new("L", (ss_px, ss_px), 0)
    draw = ImageDraw.Draw(mask_img)

    if shape == "circle":
        draw.ellipse(
            [center - radius, center - radius, center + radius, center + radius],
            fill=255,
        )
    elif shape == "rounded-square":
        corner = MASK_DIAMETER_DP * ROUNDED_SQUARE_CORNER_FRACTION * scale
        draw.rounded_rectangle(
            [center - radius, center - radius, center + radius, center + radius],
            radius=corner,
            fill=255,
        )
    elif shape == "squircle":
        # Superellipse |x/a|^n + |y/a|^n = 1, n=4, a=radius, traced as a
        # filled polygon (no numpy — cairosvg + Pillow are the only imaging
        # libraries this pipeline depends on, see issue #56/#60).
        n = 4
        steps = 360
        points = []
        for i in range(steps):
            t = 2 * math.pi * i / steps
            cos_t, sin_t = math.cos(t), math.sin(t)
            x = center + radius * math.copysign(abs(cos_t) ** (2.0 / n), cos_t)
            y = center + radius * math.copysign(abs(sin_t) ** (2.0 / n), sin_t)
            points.append((x, y))
        draw.polygon(points, fill=255)
    else:
        raise ValueError(f"unknown mask shape: {shape}")

    return mask_img.resize((px, px), Image.LANCZOS)


def _apply_mask(img: Image.Image, shape: str) -> Image.Image:
    px = img.size[0]
    mask = _mask(shape, px)
    r, g, b, a = img.split()
    combined_alpha = Image.composite(a, Image.new("L", (px, px), 0), mask)
    out = Image.merge("RGBA", (r, g, b, combined_alpha))
    return out


def render_masked_preview(
    foreground_xml: Path,
    color_map: dict[str, str],
    background_hex: str,
    shape: str,
    px: int,
) -> Image.Image:
    background = _flat_layer(background_hex, px)
    foreground = _render_vector_layer(foreground_xml, color_map, px)
    composed = Image.alpha_composite(background, foreground)
    return _apply_mask(composed, shape)


def render_monochrome_preview(
    monochrome_xml: Path,
    wallpaper_hex: str,
    tint_hex: str,
    px: int,
) -> Image.Image:
    # The monochrome VectorDrawable is authored with solid #FF000000 paths;
    # the system applies its own tint at runtime rather than using that
    # colour. We approximate that by re-colouring: render the silhouette as
    # a pure alpha mask (black paths -> full alpha where painted), then
    # composite `tint_hex` through that alpha mask over the wallpaper.
    background = _flat_layer(wallpaper_hex, px)
    silhouette = _render_vector_layer(monochrome_xml, {}, px)
    _, _, _, alpha = silhouette.split()
    tint_rgb, _ = _hex_to_rgb_and_alpha(tint_hex)
    r, g, b = Image.new("RGB", (1, 1), tint_rgb).getpixel((0, 0))
    tint_layer = Image.new("RGBA", (px, px), (r, g, b, 0))
    tint_layer.putalpha(alpha)
    composed = Image.alpha_composite(background, tint_layer)
    return composed  # monochrome previews are not masked (matches PR #58 committed files)


def render_store_icon(foreground_xml: Path, color_map: dict[str, str], background_hex: str, px: int = 512) -> Image.Image:
    background = _flat_layer(background_hex, px)
    foreground = _render_vector_layer(foreground_xml, color_map, px)
    return Image.alpha_composite(background, foreground)


# ---------------------------------------------------------------------------
# Pixel-level regression check (issue #60 requirement).
# ---------------------------------------------------------------------------


def assert_groove_present(foreground_xml: Path, color_map: dict[str, str], label: str) -> None:
    """Sample across the accent band and assert the transparent groove exists.

    Renders the raw vector layer alone (no background composited in) at high
    resolution and checks that immediately outside the accent band's edges,
    alpha drops to (near) zero — the evenOdd-cut groove — rather than
    staying opaque capsule colour, which is exactly the failure `@rev` found
    on PR #58. This must fail loudly (raise) if the groove is absent, so a
    future regression in the fill-rule handling is caught by running this
    script, not by a human eyeballing the PNGs.
    """
    px = 1080  # 10px per dp for a precise, unambiguous sample
    scale = px / CANVAS_DP
    layer = _render_vector_layer(foreground_xml, color_map, px)

    # Geometry shared by both Candidate A and Candidate B: the hole cutout
    # in the evenOdd path spans a narrower rectangle than the outer capsule,
    # and the accent overlay is narrower still, leaving a groove between the
    # accent's edge and the hole's edge. Locate it generically: walk outward
    # in dp from the canvas center along y = the vertical midpoint of the
    # accent band's own bounding path, and find the accent-color region and
    # confirm at least one pixel just outside it is transparent.
    y_dp = 54.0
    y_px = int(round(y_dp * scale))

    accent_rgb, _ = _hex_to_rgb_and_alpha(color_map.get("ic_launcher_accent", CANDIDATE_ACCENT))
    accent_rgb_tuple = Image.new("RGB", (1, 1), accent_rgb).getpixel((0, 0))

    def sample(x_dp: float) -> tuple[int, int, int, int]:
        return layer.getpixel((int(round(x_dp * scale)), y_px))

    # Find the accent band's left edge by scanning outward from center.
    accent_x = None
    for x_dp_tenth in range(int(CANVAS_DP * 10)):
        x_dp = x_dp_tenth / 10.0
        px_color = sample(x_dp)
        if px_color[3] > 200 and _close(px_color[:3], accent_rgb_tuple):
            accent_x = x_dp
            break
    if accent_x is None:
        raise AssertionError(
            f"[{label}] could not find the accent-coloured band at all in the "
            f"rendered layer — geometry regression, not just a groove regression"
        )

    # Walk left from the found accent pixel until we leave the accent colour;
    # that is the accent band's left edge. Then keep walking left through the
    # groove (must go transparent) and confirm we reach the capsule colour
    # again (opaque, non-accent) before too long — i.e. a real bounded groove,
    # not the whole capsule having gone missing.
    x = accent_x
    while True:
        c = sample(x)
        if not (c[3] > 200 and _close(c[:3], accent_rgb_tuple)):
            break
        x -= 0.1
    edge_of_accent = x

    groove_found = False
    capsule_rgb, _ = _hex_to_rgb_and_alpha(color_map.get("ic_launcher_capsule", CANDIDATE_CAPSULE))
    capsule_rgb_tuple = Image.new("RGB", (1, 1), capsule_rgb).getpixel((0, 0))
    saw_capsule_again = False
    x = edge_of_accent
    for _ in range(80):  # scan up to 8dp further left
        x -= 0.1
        c = sample(x)
        if c[3] < 40:
            groove_found = True
        if groove_found and c[3] > 200 and _close(c[:3], capsule_rgb_tuple):
            saw_capsule_again = True
            break

    if not groove_found:
        raise AssertionError(
            f"[{label}] no transparent groove found beside the accent band at "
            f"y={y_dp}dp scanning left from x={edge_of_accent:.1f}dp — this is "
            f"exactly the evenOdd fill-rule regression from issue #60. Sampled "
            f"pixel alpha never dropped below 40 within 8dp of the accent edge."
        )
    if not saw_capsule_again:
        raise AssertionError(
            f"[{label}] found a transparent gap but never reached solid capsule "
            f"colour again — groove may have eaten the whole capsule mass, not "
            f"just cut a thin sliver as designed"
        )


def _close(a: tuple[int, int, int], b: tuple[int, int, int], tol: int = 12) -> bool:
    return all(abs(x - y) <= tol for x, y in zip(a, b))


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------


def main() -> None:
    candidate_a_fg = CANDIDATES_DIR / "candidateA_foreground_not_shipped.xml"
    candidate_a_mono = CANDIDATES_DIR / "candidateA_monochrome_not_shipped.xml"
    candidate_b_fg = CANDIDATES_DIR / "candidateB_foreground_historical_not_shipped.xml"
    candidate_b_mono = CANDIDATES_DIR / "candidateB_monochrome_historical_not_shipped.xml"

    color_map = {
        "ic_launcher_capsule": CANDIDATE_CAPSULE,
        "ic_launcher_accent": CANDIDATE_ACCENT,
        "ic_launcher_background": CANDIDATE_BACKGROUND,
    }

    # --- Regression check FIRST: fail loudly before writing any PNGs if the
    # evenOdd groove is not being rendered. ---
    for label, fg in (
        ("Candidate A", candidate_a_fg),
        ("Candidate B", candidate_b_fg),
    ):
        assert_groove_present(fg, color_map, label)
    print("Pixel-level groove check: PASS for Candidate A and Candidate B")

    sizes = {"48dp": 384, "72dp": 576}
    shapes = ["circle", "squircle", "rounded-square"]

    jobs = [
        ("candidateA_not_shipped", candidate_a_fg, candidate_a_mono),
        ("candidateB_shipped", candidate_b_fg, candidate_b_mono),
    ]

    for prefix, fg_xml, mono_xml in jobs:
        for size_label, px in sizes.items():
            for shape in shapes:
                img = render_masked_preview(fg_xml, color_map, CANDIDATE_BACKGROUND, shape, px)
                out = PREVIEWS_DIR / f"{prefix}_{size_label}_{shape}.png"
                img.save(out)
                print(f"wrote {out.relative_to(REPO_ROOT)}")
            for wallpaper_label, (wallpaper_hex, tint_hex) in {
                "dark-wallpaper": (DARK_WALLPAPER, LIGHT_WALLPAPER),
                "light-wallpaper": (LIGHT_WALLPAPER, DARK_WALLPAPER),
            }.items():
                img = render_monochrome_preview(mono_xml, wallpaper_hex, tint_hex, px)
                out = PREVIEWS_DIR / f"{prefix}_monochrome_{wallpaper_label}_{size_label}.png"
                img.save(out)
                print(f"wrote {out.relative_to(REPO_ROOT)}")

    store_img = render_store_icon(candidate_a_fg, color_map, CANDIDATE_BACKGROUND, px=512)
    store_out = STORE_DIR / "ic_store_candidateA_not_shipped_512.png"
    store_img.save(store_out)
    print(f"wrote {store_out.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
