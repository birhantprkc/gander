#!/usr/bin/env python3
"""Feature graphic, 1024x500 - the nine format tiles, full width.

Two attempts failed the same way. The first was eleven anonymous colour tiles: nothing
real, nothing named. The second fanned four real rendered pages across the right - but a
page at this size is a rectangle of illegible grey text, so the concept was carried
entirely by the little format chips clipped to their corners, and the frame was still
split type-left / clutter-right.

So: make the chips the subject. The nine tiles on Gander's own welcome screen are the
strongest thing in the whole listing at small size - big blocks of colour that name
themselves - and nine of them in a row is the one composition that actually fits a 2:1
frame. They ride the panorama's wave, and each pools a little of its own colour into the
ground.

Rendered at 2x and downsampled.
"""
import math, pathlib

BUILD = pathlib.Path(__file__).parent
W, H = 1024, 500

# The app's own palette and order, from the welcome grid.
TILES = [("PDF", "#B3261E"), ("DOC", "#1565C0"), ("XLS", "#2E7D32"),
         ("PPT", "#B25000"), ("IMG", "#7B1FA2"), ("VID", "#AD1457"),
         ("AUD", "#00838F"), ("MD",  "#455A64"), ("TXT", "#616161")]

BAND_Y, AMP, PER, PHZ = 382.0, 15.0, 1240.0, 0.55
SIZE_A, SIZE_B, GAP = 84.0, 100.0, 17.0


def wave(x):
    return BAND_Y + AMP * math.sin(2 * math.pi * (x / PER) + PHZ)


def band():
    """Nine tiles laid left to right, growing slightly, each riding the wave and
    blooming its own colour into the ground beneath it."""
    n = len(TILES)
    sizes = [SIZE_A + (SIZE_B - SIZE_A) * (i / (n - 1)) for i in range(n)]
    total = sum(sizes) + GAP * (n - 1)
    x = (W - total) / 2
    out = ""
    for i, ((label, col), sz) in enumerate(zip(TILES, sizes)):
        cx = x + sz / 2
        cy = wave(cx)
        rot = 3.6 * math.sin(2 * math.pi * (cx / 470.0) + 1.1)
        # the bloom: same colour, larger and blurred, pooled under the tile
        out += (f'<div class="bloom" style="left:{cx-sz*0.72:.1f}px;top:{cy-sz*0.72:.1f}px;'
                f'width:{sz*1.44:.1f}px;height:{sz*1.44:.1f}px;background:{col};z-index:5"></div>')
        out += (f'<div class="tl" style="left:{x:.1f}px;top:{cy-sz/2:.1f}px;width:{sz:.1f}px;'
                f'height:{sz:.1f}px;background:{col};border-radius:{sz*0.235:.1f}px;'
                f'transform:rotate({rot:.2f}deg);font-size:{sz*0.285:.1f}px;z-index:7">{label}</div>')
        x += sz + GAP
    return out


def sweep(base, amp, per, ph=0.0, z=4):
    pts = " L ".join(f"{x},{base + amp*math.sin(2*math.pi*(x/per)+ph):.1f}" for x in range(0, W + 1, 16))
    return f'''<svg style="position:absolute;left:0;top:0;z-index:{z};pointer-events:none"
      width="{W}" height="{H}"><defs>
      <linearGradient id="fd" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#F2795A" stop-opacity="0"/>
        <stop offset="16%" stop-color="#F2795A" stop-opacity=".8"/>
        <stop offset="50%" stop-color="#FF9E7A" stop-opacity="1"/>
        <stop offset="86%" stop-color="#F2795A" stop-opacity=".75"/>
        <stop offset="100%" stop-color="#F2795A" stop-opacity="0"/></linearGradient>
      <filter id="sf"><feGaussianBlur stdDeviation="11"/></filter></defs>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="30" opacity=".26" filter="url(#sf)"/>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="2.4" stroke-linecap="round" opacity=".9"/>
    </svg>'''


CSS = f"""
*{{box-sizing:border-box;margin:0}} body{{background:#000}}
.fg{{position:relative;width:{W}px;height:{H}px;overflow:hidden;font-family:Poppins,sans-serif;
  background:
    radial-gradient(820px 520px at 12% -22%, rgba(242,121,90,.16), transparent 60%),
    radial-gradient(900px 540px at 86% 128%, rgba(120,150,210,.10), transparent 62%),
    linear-gradient(97deg,#1C160E 0%,#131009 40%,#181209 70%,#100C07 100%);}}
.bloom{{position:absolute;border-radius:50%;filter:blur(26px);opacity:.24;pointer-events:none}}
.tl{{position:absolute;display:flex;align-items:center;justify-content:center;
  color:#fff;font-weight:700;letter-spacing:.005em;
  box-shadow:inset 0 1.5px 0 rgba(255,255,255,.20),
             0 2px 4px rgba(0,0,0,.45), 0 16px 34px rgba(0,0,0,.55)}}
.lock{{position:absolute;right:64px;top:56px;width:176px;z-index:9}}
.kicker{{position:absolute;left:64px;top:100px;z-index:9;color:#F8EFE0;font-size:48px;
  font-weight:500;letter-spacing:-.02em;text-shadow:0 2px 18px rgba(0,0,0,.6)}}
.hero{{position:absolute;left:60px;top:152px;z-index:9;color:#F2795A;font-size:96px;
  font-weight:700;letter-spacing:-.045em;line-height:.94;text-shadow:0 2px 24px rgba(0,0,0,.6)}}
.sub{{position:absolute;left:66px;top:276px;z-index:9;color:#9C9080;font-size:17.5px;
  font-weight:500;letter-spacing:.015em}}
.vig{{position:absolute;inset:0;z-index:12;pointer-events:none;
  background:linear-gradient(to bottom, rgba(0,0,0,.22), transparent 16%, transparent 86%, rgba(0,0,0,.30))}}
"""


def build(out="fg3.html"):
    body = (sweep(470, 14, 1240, 0.55, z=4) + band()
            + f'<img class="lock" src="{BUILD}/lockup.png">'
            + '<div class="kicker">Take a gander at</div>'
            + '<div class="hero">any file.</div>'
            + '<div class="sub">Offline viewer &middot; no permissions &middot; no trackers</div>'
            + '<div class="vig"></div>')
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
        f'<style>{CSS}</style></head><body><div class="fg">{body}</div></body></html>')
    return out


if __name__ == "__main__":
    print("wrote", build())
