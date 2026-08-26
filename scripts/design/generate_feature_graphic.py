#!/usr/bin/env python3
"""Generate Google Play Store 1024x500 Feature Graphic.

Spec: 1024 x 500 px, 24-bit PNG (RGB, no alpha channel) or JPEG.
Outputs: docs/design/store/feature_graphic_1024x500.png
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

WIDTH = 1024
HEIGHT = 500
OUTPUT_PATH = "docs/design/store/feature_graphic_1024x500.png"
ICON_PATH = "docs/design/store/ic_launcher_store_512.png"

def create_feature_graphic():
    # 1. Base dark canvas (RGB, no alpha)
    img = Image.new("RGB", (WIDTH, HEIGHT), color=(20, 21, 26))

    # Gradient background: subtle vertical gradient and warm accent ambient glow
    glow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    
    # Warm amber glow centered around the icon on the left
    icon_center_x, icon_center_y = 260, 250
    for r in range(300, 0, -4):
        alpha = int(32 * (1.0 - r / 300.0) ** 1.5)
        glow_draw.ellipse(
            [icon_center_x - r, icon_center_y - r, icon_center_x + r, icon_center_y + r],
            fill=(235, 110, 30, alpha),
        )

    # Subtle top-right accent glow
    for r in range(250, 0, -5):
        alpha = int(18 * (1.0 - r / 250.0))
        glow_draw.ellipse(
            [850 - r, 100 - r, 850 + r, 100 + r],
            fill=(90, 110, 160, alpha),
        )

    img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")
    draw = ImageDraw.Draw(img)

    # Subtle border styling
    draw.line([(0, 0), (WIDTH, 0)], fill=(42, 44, 52), width=1)
    draw.line([(0, HEIGHT - 1), (WIDTH, HEIGHT - 1)], fill=(32, 34, 40), width=1)

    # 2. Draw App Icon with rounded corners and drop shadow
    if os.path.exists(ICON_PATH):
        raw_icon = Image.open(ICON_PATH).convert("RGBA")
        icon_size = 260
        icon_resized = raw_icon.resize((icon_size, icon_size), Image.Resampling.LANCZOS)

        # Rounded corner mask for the icon
        mask = Image.new("L", (icon_size, icon_size), 0)
        mask_draw = ImageDraw.Draw(mask)
        mask_draw.rounded_rectangle([0, 0, icon_size, icon_size], radius=56, fill=255)
        
        # Apply mask
        icon_rounded = Image.new("RGBA", (icon_size, icon_size), (0, 0, 0, 0))
        icon_rounded.paste(icon_resized, (0, 0), mask)

        # Draw subtle border around icon
        border_overlay = Image.new("RGBA", (icon_size, icon_size), (0, 0, 0, 0))
        border_draw = ImageDraw.Draw(border_overlay)
        border_draw.rounded_rectangle(
            [0, 0, icon_size - 1, icon_size - 1],
            radius=56,
            outline=(255, 255, 255, 60),
            width=2,
        )
        icon_rounded = Image.alpha_composite(icon_rounded, border_overlay)

        # Drop shadow
        shadow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        icon_x = 130
        icon_y = (HEIGHT - icon_size) // 2
        shadow_draw.rounded_rectangle(
            [icon_x + 8, icon_y + 14, icon_x + icon_size + 8, icon_y + icon_size + 14],
            radius=56,
            fill=(0, 0, 0, 180),
        )
        shadow = shadow.filter(ImageFilter.GaussianBlur(20))
        
        img_rgba = img.convert("RGBA")
        img_rgba = Image.alpha_composite(img_rgba, shadow)
        img_rgba.paste(icon_rounded, (icon_x, icon_y), icon_rounded)
        img = img_rgba.convert("RGB")
        draw = ImageDraw.Draw(img)

    # 3. Typography
    font_bold_candidates = [
        "/usr/share/fonts/truetype/ubuntu/Ubuntu-B.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    font_bold_path = next((p for p in font_bold_candidates if os.path.exists(p)), None)
    
    font_reg_candidates = [
        "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    font_reg_path = next((p for p in font_reg_candidates if os.path.exists(p)), None)

    title_font = ImageFont.truetype(font_bold_path, 52) if font_bold_path else ImageFont.load_default()
    subtitle_font = ImageFont.truetype(font_reg_path, 22) if font_reg_path else ImageFont.load_default()
    badge_font = ImageFont.truetype(font_bold_path, 13) if font_bold_path else ImageFont.load_default()
    bullet_font = ImageFont.truetype(font_reg_path, 17) if font_reg_path else ImageFont.load_default()

    text_x = 450
    
    # Badge "100% PRIVATE • ZERO NETWORK EGRESS"
    badge_text = "100% PRIVATE • ZERO NETWORK EGRESS"
    badge_w = 320
    badge_h = 28
    badge_y = 125
    draw.rounded_rectangle(
        [text_x, badge_y, text_x + badge_w, badge_y + badge_h],
        radius=14,
        fill=(38, 42, 50),
        outline=(70, 76, 88),
        width=1,
    )
    draw.text((text_x + 16, badge_y + 6), badge_text, fill=(255, 175, 95), font=badge_font)

    # Title
    title_y = 170
    draw.text((text_x, title_y), "Audio Blackbox", fill=(255, 255, 255), font=title_font)

    # Subtitles
    draw.text((text_x, 245), "Continuous ambient audio buffer.", fill=(225, 228, 235), font=subtitle_font)
    draw.text((text_x, 280), "Save the recent past, on demand.", fill=(160, 166, 180), font=subtitle_font)

    # Bullets (stacked cleanly)
    bullets_y = 330
    bullets = [
        "✓ Rolling RAM buffer (5 to 60 min)",
        "✓ Single-tap instant save to device storage",
        "✓ Pauses automatically during phone calls",
    ]
    for i, b in enumerate(bullets):
        draw.text((text_x, bullets_y + i * 28), b, fill=(195, 200, 212), font=bullet_font)

    # Save 24-bit PNG without alpha
    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    img.save(OUTPUT_PATH, "PNG", optimize=True)
    print(f"Generated {OUTPUT_PATH} ({img.size[0]}x{img.size[1]}, mode={img.mode})")

if __name__ == "__main__":
    create_feature_graphic()
