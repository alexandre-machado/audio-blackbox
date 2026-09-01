from PIL import Image, ImageDraw

poly0 = [(10.76,21.14),(10.08,21.08),(9.38,20.83),(4.31,17.47),(4.10,17.04),(4.06,16.60),(3.85,16.39),(3.54,16.22),(3.44,16.03),(3.32,15.52),(3.35,15.36),(3.30,15.31),(3.33,15.12),(3.16,14.95),(2.96,15.01),(2.55,14.88),(2.40,14.79),(2.25,14.48),(2.22,13.99),(2.18,13.95),(2.21,13.81),(2.17,13.78),(2.19,13.62),(2.15,13.58),(2.18,13.40),(2.14,13.36),(2.17,13.15),(2.12,13.10),(2.15,13.05),(2.15,12.78),(2.00,12.61),(2.14,12.47),(2.14,12.35),(2.05,12.25),(2.15,12.15),(2.13,11.90),(2.17,11.87),(2.21,11.34),(2.38,10.88),(2.50,10.74),(2.70,10.63),(3.79,10.28),(3.98,10.10),(4.15,9.53),(4.49,8.98),(4.74,8.73),(5.17,8.46),(5.58,8.31),(10.55,6.98),(10.74,6.79),(10.71,5.24),(10.88,4.68),(11.19,4.21),(11.47,4.02),(11.91,3.84),(15.46,2.94),(15.89,2.86),(16.30,2.86),(16.95,3.00),(20.66,4.04),(21.22,4.30),(21.56,4.65),(21.81,5.24),(21.85,5.47),(21.83,6.88),(22.00,7.05),(22.00,7.33),(21.82,7.51),(21.82,8.69),(21.98,8.86),(21.98,9.09),(21.81,9.26),(21.81,10.49),(21.97,10.66),(21.97,10.79),(21.79,10.97),(21.79,12.08),(21.92,12.21),(21.78,12.35),(21.77,12.61),(21.55,13.12),(21.75,13.47),(21.86,13.79),(21.85,13.90),(21.90,13.95),(21.86,13.99),(21.87,15.01),(21.69,15.55),(21.43,15.80),(11.93,20.75),(11.39,20.99),(10.76,21.14)]
poly1 = [(10.67,19.71),(11.07,19.65),(11.47,19.51),(21.43,14.52),(21.82,14.14),(21.81,13.80),(21.68,13.48),(21.47,13.27),(21.22,13.49),(20.81,13.70),(17.87,15.11),(17.45,15.23),(17.07,15.26),(16.52,15.18),(14.77,14.42),(14.50,14.61),(14.11,14.81),(9.01,16.78),(8.48,16.95),(7.95,17.05),(7.13,17.03),(6.96,17.21),(7.04,17.30),(5.54,16.37),(5.15,16.50),(4.96,16.68),(5.15,16.87),(9.30,19.47),(9.89,19.67),(10.16,19.71),(10.67,19.71)]

SCALE = 20  # px per viewport unit -> 480x480 for 24 viewport
W = H = 24*SCALE

def rasterize_evenodd(polys, w, h, scale):
    img = Image.new("L", (w, h), 0)
    px = img.load()
    edges = []
    for poly in polys:
        n = len(poly)
        for i in range(n):
            x1,y1 = poly[i]
            x2,y2 = poly[(i+1)%n]
            edges.append((x1*scale,y1*scale,x2*scale,y2*scale))
    for y in range(h):
        yc = y + 0.5
        xs = []
        for (x1,y1,x2,y2) in edges:
            if y1 == y2:
                continue
            if (y1 <= yc < y2) or (y2 <= yc < y1):
                t = (yc - y1) / (y2 - y1)
                x = x1 + t*(x2-x1)
                xs.append(x)
        xs.sort()
        for i in range(0, len(xs)-1, 2):
            xstart = xs[i]; xend = xs[i+1]
            xs_i = max(0, int(round(xstart)))
            xe_i = min(w, int(round(xend)))
            for x in range(xs_i, xe_i):
                px[x,y] = 255
    return img

mask = rasterize_evenodd([poly0, poly1], W, H, SCALE)

def compose(bg_color, fg_rgba, out_path, label_note):
    canvas = Image.new("RGB", (W, H), bg_color)
    fg = Image.new("RGBA", (W, H), fg_rgba)
    canvas.paste(fg, (0,0), mask)
    canvas.save(out_path)
    print(out_path, label_note)

OUT = "/tmp/claude-1000/-home-ubuntu-24-repos-alexandre-machado-audio-blackbox/56ddb723-10b4-40c7-8817-e19e73839c22/scratchpad"

# Active (tinted-on): QS tile active background is typically a filled accent circle with
# on-accent (near-white/very light) glyph. Approximate with a filled cockpit accent behind
# and a bright glyph.
compose((0x21,0x21,0x21), (0xFF,0xFF,0xFF,255), f"{OUT}/tile_active_24dp.png", "active (tinted-on): dark tile bg, white/tinted glyph")

# Inactive (dimmed-off): system dims glyph tint (mid-gray on dark bg), no accent fill.
compose((0x21,0x21,0x21), (0x9E,0x9E,0x9E,255), f"{OUT}/tile_inactive_24dp.png", "inactive (dimmed-off): dark tile bg, dimmed gray glyph")
