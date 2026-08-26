#!/usr/bin/env python3
"""Generate isolated transparent launcher icon and Play Store icon from source art.

Removes the opaque white background and the ground shadow patch from docs/design/icon/icon.1.jpg,
producing:
- docs/design/store/ic_launcher_store_512.png (512x512 PNG, 32-bit RGBA)
- app/src/main/res/drawable-{m,h,x,xx,xxx}hdpi/ic_launcher_foreground.png (108dp adaptive foregrounds)
"""

import os
from collections import deque
from PIL import Image, ImageFilter

SOURCE_JPG = "docs/design/icon/icon.1.jpg"
STORE_ICON_PNG = "docs/design/store/ic_launcher_store_512.png"

DENSITIES = {
    "drawable-mdpi": 108,
    "drawable-hdpi": 162,
    "drawable-xhdpi": 216,
    "drawable-xxhdpi": 324,
    "drawable-xxxhdpi": 432,
}

def is_flight_recorder(r, g, b):
    # Dark brown / dark orange outline
    if r < 130 and g < 75 and b < 55 and r >= g and g >= b:
        return True
    # Dark metal connector / grey outline
    if r < 80 and g < 80 and b < 80:
        return True
    # Orange / Amber body
    if r > 140 and (r - b > 40):
        return True
    return False

def extract_isolated_recorder(src_image):
    w, h = src_image.size
    pixels = src_image.load()

    # Flood fill from image borders to find all background & ground shadow pixels
    visited = bytearray(w * h)
    queue = deque()

    for x in range(w):
        queue.append((x, 0))
        queue.append((x, h - 1))
        visited[0 * w + x] = 1
        visited[(h - 1) * w + x] = 1

    for y in range(h):
        queue.append((0, y))
        queue.append((w - 1, y))
        visited[y * w + 0] = 1
        visited[y * w + (w - 1)] = 1

    while queue:
        cx, cy = queue.popleft()
        for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
            if 0 <= nx < w and 0 <= ny < h:
                idx = ny * w + nx
                if not visited[idx]:
                    r, g, b, _ = pixels[nx, ny]
                    if not is_flight_recorder(r, g, b):
                        visited[idx] = 1
                        queue.append((nx, ny))

    # Construct raw alpha mask
    mask = Image.new("L", (w, h), 0)
    mask_pixels = mask.load()
    for y in range(h):
        for x in range(w):
            if not visited[y * w + x]:
                mask_pixels[x, y] = 255

    # Refine mask edges with smooth anti-aliasing
    smooth_mask = mask.filter(ImageFilter.GaussianBlur(radius=0.75))

    # Extract clean foreground on transparent canvas
    clean_fg = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    clean_fg.paste(src_image, (0, 0), smooth_mask)

    # Crop to tight bounding box
    bbox = clean_fg.getbbox()
    cropped = clean_fg.crop(bbox)
    return cropped

def generate_icons():
    print(f"Loading source artwork from {SOURCE_JPG}...")
    src = Image.open(SOURCE_JPG).convert("RGBA")
    cropped_recorder = extract_isolated_recorder(src)
    cw, ch = cropped_recorder.size
    print(f"Isolated flight recorder bounding box: {cw}x{ch} px")

    # 1. Generate 512x512 Store Icon (Transparent RGBA)
    store_target_dim = 410
    scale_store = store_target_dim / max(cw, ch)
    sw = int(cw * scale_store)
    sh = int(ch * scale_store)
    store_resized = cropped_recorder.resize((sw, sh), Image.Resampling.LANCZOS)

    store_icon = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    store_icon.paste(store_resized, ((512 - sw) // 2, (512 - sh) // 2), store_resized)
    os.makedirs(os.path.dirname(STORE_ICON_PNG), exist_ok=True)
    store_icon.save(STORE_ICON_PNG, "PNG", optimize=True)
    print(f"Saved {STORE_ICON_PNG} (512x512, mode={store_icon.mode})")

    # 2. Generate Adaptive Icon Foregrounds for all densities
    # In Android adaptive icons (108dp canvas), the visible circle is 72dp (~66.6% of canvas).
    # The flight recorder should span ~60-64dp to fit comfortably in the safe zone.
    for folder, size_px in DENSITIES.items():
        # Target size for object: ~58% of canvas size
        target_dim = int(size_px * 0.58)
        scale = target_dim / max(cw, ch)
        nw = int(cw * scale)
        nh = int(ch * scale)
        resized = cropped_recorder.resize((nw, nh), Image.Resampling.LANCZOS)

        canvas = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
        paste_x = (size_px - nw) // 2
        paste_y = (size_px - nh) // 2
        canvas.paste(resized, (paste_x, paste_y), resized)

        out_path = os.path.join("app/src/main/res", folder, "ic_launcher_foreground.png")
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        canvas.save(out_path, "PNG", optimize=True)
        print(f"Saved {out_path} ({size_px}x{size_px})")

if __name__ == "__main__":
    generate_icons()
