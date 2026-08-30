"""Static layers for the promo video: ground, device frame, screen mask, captions.

Same palette and device language as the v1.14 store frames (build/pano.py, build/fg7.py),
rendered with Pillow instead of Chrome so the output is deterministic and needs no network.
Everything is 1920x1080; the phone screen is a 1080x1920 recording scaled to SCREEN.
"""
import os, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "layers")
FONT = os.path.join(HERE, "Jost.ttf")
LOCKUP = os.path.join(HERE, "..", "build", "lockup.png")

W, H = 1920, 1080
GROUND = (0x17, 0x13, 0x0C)
INK    = (0xF8, 0xEF, 0xE0)
MUTED  = (0xCF, 0xC5, 0xB2)
CORAL  = (0xE2, 0x79, 0x5F)
DIM    = (0x6F, 0x67, 0x58)

# Phone geometry. Screen keeps 1080:1920 exactly (500x889 is 0.5624 vs 0.5625).
SCREEN = (1150, 96, 500, 889)          # x, y, w, h
PAD = 12                               # bezel; pano.py uses 14 at a ~612px screen
OUTER_R, INNER_R = 64, 52
M = 150                                # left margin of the type column, as fg7
COL_W = 900                            # max caption width


def jost(size, weight):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_axes([weight])
    return f


def rounded_mask(w, h, r, scale=4):
    """Anti-aliased rounded-rect alpha mask, supersampled."""
    m = Image.new("L", (w * scale, h * scale), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * scale - 1, h * scale - 1], r * scale, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def linear_gradient(w, h, angle_deg, stops):
    """CSS-style linear-gradient(angle, stops) as an RGBA array. stops: [(t, (r,g,b,a)), ...]."""
    a = math.radians(angle_deg)
    dx, dy = math.sin(a), -math.cos(a)            # CSS: 0deg points up, 90deg right
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    proj = (xs - w / 2) * dx + (ys - h / 2) * dy
    half = abs(w / 2 * dx) + abs(h / 2 * dy)
    t = np.clip((proj + half) / (2 * half), 0, 1)
    out = np.zeros((h, w, 4), np.float32)
    ts = [s[0] for s in stops]
    for c in range(4):
        vals = [s[1][c] for s in stops]
        out[..., c] = np.interp(t, ts, vals)
    return out


def ground():
    """Flat warm near-black, a glow behind the phone, a top/bottom vignette, grain last."""
    img = np.zeros((H, W, 3), np.float32)
    img[...] = GROUND
    # glow: radial, closest-side, like Concept C's .glow behind each device
    sx, sy, sw, sh = SCREEN
    cx, cy = sx + sw / 2, sy + sh / 2
    gw, gh = (sw + 2 * PAD + 80) / 2, (sh + 2 * PAD + 320) / 2
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt(((xs - cx) / gw) ** 2 + ((ys - cy) / gh) ** 2)
    glow = np.clip(1 - d / 0.72, 0, 1) * 0.17
    img += glow[..., None] * (np.array((255, 176, 140), np.float32) - img)
    # vignette
    v = np.zeros(H, np.float32)
    top = np.clip((0.16 * H - np.arange(H)) / (0.16 * H), 0, 1) * 0.34
    bot = np.clip((np.arange(H) - 0.76 * H) / (0.24 * H), 0, 1) * 0.42
    v = np.maximum(top, bot)
    img *= (1 - v)[:, None, None]
    # grain, added not blended, stddev ~3 as fg7
    rng = np.random.default_rng(7)
    img += rng.normal(0, 3.0, img.shape).astype(np.float32)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(OUT, "ground.png"))


def frame():
    """Device frame with a transparent screen hole, sheen over the hole, rim-lit, deep shadows."""
    sx, sy, sw, sh = SCREEN
    fx, fy, fw, fh = sx - PAD, sy - PAD, sw + 2 * PAD, sh + 2 * PAD
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # shadows: three drops, as pano.py's .phone box-shadow
    for (oy, blur, alpha) in ((3, 4, 0.55), (34, 40, 0.55), (90, 110, 0.60)):
        sh_l = Image.new("L", (W, H), 0)
        sh_l.paste(rounded_mask(fw, fh, OUTER_R), (fx, fy + oy))
        sh_l = sh_l.filter(ImageFilter.GaussianBlur(blur))
        sh_a = (np.asarray(sh_l, np.float32) * alpha).astype(np.uint8)
        black = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        black.putalpha(Image.fromarray(sh_a))
        layer = Image.alpha_composite(layer, black)
    # bezel body: 155deg gradient #4A4238 -> #1A160F 26% -> #0E0C08 58% -> #2C2620
    g = linear_gradient(fw, fh, 155, [(0, (0x4A, 0x42, 0x38, 255)), (0.26, (0x1A, 0x16, 0x0F, 255)),
                                     (0.58, (0x0E, 0x0C, 0x08, 255)), (1, (0x2C, 0x26, 0x20, 255))])
    body = Image.fromarray(g.astype(np.uint8), "RGBA")
    body.putalpha(rounded_mask(fw, fh, OUTER_R))
    # 1px rim: rgba(255,255,255,.07) outside the body
    rim = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    rm = np.asarray(rounded_mask(fw + 2, fh + 2, OUTER_R + 1), np.float32)
    inner = np.zeros_like(rm); inner[1:-1, 1:-1] = np.asarray(rounded_mask(fw, fh, OUTER_R), np.float32)
    rim_a = np.clip(rm - inner, 0, 255) * 0.07
    rim_img = Image.new("RGBA", (fw + 2, fh + 2), (255, 255, 255, 255))
    rim_img.putalpha(Image.fromarray(rim_a.astype(np.uint8)))
    rim.paste(rim_img, (fx - 1, fy - 1), rim_img)
    layer = Image.alpha_composite(layer, rim)
    full = Image.new("RGBA", (W, H), (0, 0, 0, 0)); full.paste(body, (fx, fy), body)
    layer = Image.alpha_composite(layer, full)
    # cut the screen hole, then lay the sheen over it (122deg white .11 -> .03 at 17% -> 0 at 34%)
    hole = Image.new("L", (W, H), 255)
    hole.paste(Image.fromarray(255 - np.asarray(rounded_mask(sw, sh, INNER_R))), (sx, sy))
    a = np.asarray(layer.getchannel("A"), np.float32) * (np.asarray(hole, np.float32) / 255)
    layer.putalpha(Image.fromarray(a.astype(np.uint8)))
    # inset 1px dark edge on the screen (pano.py: inset 0 0 0 1px rgba(0,0,0,.85))
    edge = np.asarray(rounded_mask(sw, sh, INNER_R), np.float32)
    edge_in = np.zeros_like(edge); edge_in[1:-1, 1:-1] = np.asarray(rounded_mask(sw - 2, sh - 2, INNER_R - 1), np.float32)
    edge_a = np.clip(edge - edge_in, 0, 255) * 0.85
    sheen = linear_gradient(sw, sh, 122, [(0, (255, 255, 255, 28)), (0.17, (255, 255, 255, 8)), (0.34, (255, 255, 255, 0)), (1, (255, 255, 255, 0))])
    sheen_img = Image.fromarray(sheen.astype(np.uint8), "RGBA")
    sa = np.asarray(sheen_img.getchannel("A"), np.float32) * (edge / 255)
    sheen_img.putalpha(Image.fromarray(sa.astype(np.uint8)))
    edge_img = Image.new("RGBA", (sw, sh), (0, 0, 0, 255)); edge_img.putalpha(Image.fromarray(edge_a.astype(np.uint8)))
    over = Image.new("RGBA", (W, H), (0, 0, 0, 0)); over.paste(sheen_img, (sx, sy), sheen_img)
    over2 = Image.new("RGBA", (W, H), (0, 0, 0, 0)); over2.paste(edge_img, (sx, sy), edge_img)
    layer = Image.alpha_composite(Image.alpha_composite(layer, over), over2)
    layer.save(os.path.join(OUT, "frame.png"))
    # screen mask for the recording (ffmpeg alphamerge): white rounded rect at the screen size
    rounded_mask(sw, sh, INNER_R).save(os.path.join(OUT, "screenmask.png"))


def fit(text, weight, size, max_w):
    while size > 40:
        f = jost(size, weight)
        if f.getlength(text) <= max_w:
            return f, size
        size -= 2
    return jost(size, weight), size


def caption(name, l1, l2, sub, size=118, sub_size=30, sub_color=MUTED, extra=()):
    """Transparent 1920x1080 layer: line 1 ink, line 2 coral, sub muted; block centred on the phone."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f1, s1 = fit(l1, 700, size, COL_W)
    f2, s2 = fit(l2, 700, s1, COL_W)
    if s2 < s1: f1, s1 = jost(s2, 700), s2
    lh = int(s1 * 0.92)
    fs = jost(sub_size, 500)
    sub_lines = sub.split("\n")
    sub_lh = int(sub_size * 1.45)
    # the sub sits below line 2's real glyph bottom (descenders), not its nominal line box
    l2_bottom = lh + f2.getbbox(l2)[3]
    block = l2_bottom + 30 + len(sub_lines) * sub_lh + sum(int(e[1] * 1.6) for e in extra)
    top = (H - block) // 2
    # Jost bold at negative tracking; Pillow has no letter-spacing, so draw glyph by glyph
    def draw_tracked(y, text, font, fill, track_em):
        x = M
        track = track_em * font.size
        for ch in text:
            d.text((x, y), ch, font=font, fill=fill)
            x += font.getlength(ch) + track
    draw_tracked(top, l1, f1, INK, -0.045)
    draw_tracked(top + lh, l2, f2, CORAL, -0.045)
    y = top + l2_bottom + 30
    for line in sub_lines:
        d.text((M, y), line, font=fs, fill=sub_color)
        y += sub_lh
    for (text, esize, color, weight) in extra:
        y += int(esize * 0.6)
        d.text((M, y), text, font=jost(esize, weight), fill=color)
        y += int(esize * 1.0)
    img.save(os.path.join(OUT, f"cap-{name}.png"))
    return s1


def wordmark():
    lk = Image.open(LOCKUP).convert("RGBA")
    h = 84          # 44 matched fg7's lockup, which vanished once Play scaled the video to a phone
    lk = lk.resize((round(lk.width * h / lk.height), h), Image.LANCZOS)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    img.paste(lk, (M, 60), lk)
    img.save(os.path.join(OUT, "wordmark.png"))


CAPTIONS = [
    ("open",    "Take a gander at", "any file.",  "Offline viewer  ·  no permissions  ·  no trackers"),
    ("opens",   "Opens",   "everything.", "PDF, Word, Excel, PowerPoint, photos,\nvideo, audio, Markdown and code."),
    ("nothing", "Takes",   "nothing.",    "No permissions. No trackers.\nNo internet access at all."),
    ("finds",   "Finds",   "anything.",   "Search inside a PDF, a spreadsheet\nor a deck. Match by match."),
    ("sheet",   "Every",   "sheet.",      "Multi-sheet workbooks, tabs and all.\nxlsx, xls, xlsm, xlsb, csv, ods."),
    ("deck",    "Any",     "deck.",       "PowerPoint slides, without\ninstalling an office suite."),
    ("folders", "From your", "folders.",  "Grant one once. Gander still needs\nno storage permission to read it."),
    ("2am",     "Reads",   "at 2am.",     "Follows your phone into dark mode,\neverywhere in the app."),
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    ground(); frame(); wordmark()
    for name, l1, l2, sub in CAPTIONS:
        print(name, caption(name, l1, l2, sub))
    print("end", caption("end", "Take a gander at", "any file.",
                         "Offline viewer  ·  no permissions  ·  no trackers",
                         extra=[("PDF · Word · Excel · PowerPoint · Photos · Video · Audio · Markdown · Code", 24, DIM, 500),
                                ("Free and open source  ·  github.com/mokshablr/gander", 26, MUTED, 500)]))
