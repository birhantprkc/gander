#!/usr/bin/env python3
"""Feature graphic, 1024x500 - the card procession made real.

The panorama's richest background motif is `drift.cards()`: abstract paper rectangles
with a format badge and four grey lines on them. This is that procession with the
abstraction removed - every card is an actual page rendered by Gander, cropped out of a
real capture, growing and brightening left to right the way the drawn ones do, riding the
same coral sweep. The claim the graphic has to make is "opens everything", and four
unmistakably different file types in one glance make it without a word.

Rendered at 2x and downsampled: type and hairlines at 1024 wide are otherwise mushy.
"""
import math, pathlib

BUILD = pathlib.Path(__file__).parent
# Document content cropped out of the light-theme captures in ../raw: `-crop 1080x1620+0+300`
# drops the status bar and app bar and leaves the rendered page.
PAGES = BUILD / "pages"
W, H = 1024, 500
BADGE = {"PDF": "#B3261E", "DOC": "#1565C0", "XLS": "#2E7D32", "PPT": "#B25000",
         "IMG": "#7B1FA2", "VID": "#AD1457", "AUD": "#00838F", "MD": "#455A64"}
GROUND = (0x16, 0x11, 0x0A)


def mix(hexc, t):
    c = hexc.lstrip('#'); r, g, b = (int(c[i:i+2], 16) for i in (0, 2, 4))
    m = lambda a, bb: round(a + (bb - a) * t)
    return f"#{m(r,GROUND[0]):02x}{m(g,GROUND[1]):02x}{m(b,GROUND[2]):02x}"


class R:
    def __init__(s, seed): s.x = seed
    def __call__(s, a=0.0, b=1.0):
        s.x = (1103515245 * s.x + 12345) % 2147483648
        return a + (b - a) * (s.x / 2147483648)


def wave(x, base, amp, per, ph=0.0):
    return base + amp * math.sin(2 * math.pi * (x / per) + ph)


def tiles(seed, n, base, amp, per, sa, sb, ma, mb, z=1, blur=0.0, ph=0.0, accents=()):
    """The badge-coloured tile field, at feature-graphic scale."""
    r = R(seed); out = ""
    keys = list(BADGE)
    for i in range(n):
        u = i / (n - 1)
        x = -40 + (W + 80) * u + r(-26, 26)
        y = wave(x, base, amp, per, ph) + r(-30, 30)
        sz = sa + (sb - sa) * u + r(-4, 4)
        col = BADGE[keys[i % len(keys)]] if i in accents else mix(BADGE[keys[i % len(keys)]], ma + (mb - ma) * u)
        f = f"filter:blur({blur}px);" if blur else ""
        out += (f'<div style="position:absolute;left:{x:.0f}px;top:{y:.0f}px;width:{sz:.0f}px;'
                f'height:{sz:.0f}px;background:{col};border-radius:{sz*.235:.0f}px;'
                f'transform:rotate({r(-9,9):.1f}deg);z-index:{z};{f}'
                f'box-shadow:0 4px 10px rgba(0,0,0,.45)"></div>')
    return out


def sweep(base, amp, per, ph=0.0, z=4):
    pts = " L ".join(f"{x},{wave(x,base,amp,per,ph):.1f}" for x in range(0, W + 1, 16))
    return f'''<svg style="position:absolute;left:0;top:0;z-index:{z};pointer-events:none"
      width="{W}" height="{H}"><defs>
      <linearGradient id="fd" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#F2795A" stop-opacity="0"/>
        <stop offset="18%" stop-color="#F2795A" stop-opacity=".85"/>
        <stop offset="52%" stop-color="#FF9E7A" stop-opacity="1"/>
        <stop offset="84%" stop-color="#F2795A" stop-opacity=".8"/>
        <stop offset="100%" stop-color="#F2795A" stop-opacity="0"/></linearGradient>
      <filter id="sf"><feGaussianBlur stdDeviation="9"/></filter></defs>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="24" opacity=".30" filter="url(#sf)"/>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="2.2" stroke-linecap="round" opacity=".95"/>
    </svg>'''


def card(x, w, cy, src, fmt, rot, bright, blur, z):
    """One real page. Height follows the 1080x1620 crop; the badge tags its bottom-left
    corner in the app's own format colour, the same chip the file list uses."""
    h = w * 1.5
    top = cy - h / 2
    bw = max(28, w * 0.17)
    return (
      f'<div class="card" style="left:{x:.0f}px;top:{top:.0f}px;width:{w:.0f}px;height:{h:.0f}px;'
      f'z-index:{z};transform:rotate({rot}deg);filter:brightness({bright}){" blur(%.1fpx)"%blur if blur else ""}">'
      f'<img src="{PAGES}/{src}">'
      f'<div class="gl"></div></div>'
      f'<div class="chip" style="left:{x-bw*0.38:.0f}px;top:{top+h-bw:.0f}px;width:{bw:.0f}px;'
      f'height:{bw:.0f}px;background:{BADGE[fmt]};border-radius:{bw*0.235:.0f}px;z-index:{z+1};'
      f'transform:rotate({rot}deg);font-size:{bw*0.34:.0f}px;filter:brightness({min(1.0,bright+0.12)})">{fmt}</div>')


CARDS = [
    # x,   w,   centre-y, source,   format, rot,  brightness, blur
    (480, 190, 322, "xls.png", "XLS", -4.0, 0.68, 0.7),
    (604, 226, 302, "ppt.png", "PPT", -1.4, 0.78, 0.4),
    (734, 268, 284, "img.png", "IMG",  1.2, 0.90, 0.0),
    (862, 320, 250, "pdf.png", "PDF",  3.4, 1.00, 0.0),
]

CSS = f"""
*{{box-sizing:border-box;margin:0}} body{{background:#000}}
.fg{{position:relative;width:{W}px;height:{H}px;overflow:hidden;font-family:Poppins,sans-serif;
  background:
    radial-gradient(760px 460px at 7% -16%, rgba(242,121,90,.15), transparent 62%),
    radial-gradient(820px 500px at 88% 124%, rgba(120,150,210,.09), transparent 62%),
    linear-gradient(97deg,#1C160E 0%,#131009 40%,#181209 70%,#100C07 100%);}}
/* light pooled under the procession, so the pages sit in something */
.glow{{position:absolute;left:430px;top:20px;width:660px;height:520px;z-index:3;pointer-events:none;
  background:radial-gradient(closest-side, rgba(255,176,140,.20), transparent 74%)}}
.card{{position:absolute;overflow:hidden;border-radius:9px;background:#EFE9DD;
  outline:1px solid rgba(248,239,224,.20);outline-offset:-1px;
  box-shadow:0 1px 2px rgba(0,0,0,.5),0 14px 30px rgba(0,0,0,.55),0 40px 80px rgba(0,0,0,.5)}}
.card img{{position:absolute;left:0;top:0;width:100%;display:block}}
/* a raking highlight down the leading edge, the same one the device frames carry */
.card .gl{{position:absolute;inset:0;pointer-events:none;
  background:linear-gradient(114deg, rgba(255,255,255,.22) 0%, rgba(255,255,255,.06) 14%, transparent 34%)}}
.chip{{position:absolute;display:flex;align-items:center;justify-content:center;
  color:#fff;font-weight:700;letter-spacing:.02em;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.16),0 6px 16px rgba(0,0,0,.55)}}
.lock{{position:absolute;left:64px;top:80px;width:166px;z-index:8}}
.kicker{{position:absolute;left:64px;top:168px;z-index:8;color:#F8EFE0;font-size:44px;
  font-weight:500;letter-spacing:-.02em;text-shadow:0 2px 18px rgba(0,0,0,.6)}}
.hero{{position:absolute;left:62px;top:208px;z-index:8;color:#F2795A;font-size:88px;
  font-weight:700;letter-spacing:-.045em;line-height:.94;text-shadow:0 2px 24px rgba(0,0,0,.6)}}
.sub{{position:absolute;left:66px;top:320px;z-index:8;color:#9C9080;font-size:17.5px;
  font-weight:500;letter-spacing:.015em}}
.vig{{position:absolute;inset:0;z-index:12;pointer-events:none;
  background:linear-gradient(to bottom, rgba(0,0,0,.24), transparent 16%, transparent 84%, rgba(0,0,0,.30))}}
"""


def build(out="fg2.html"):
    body = (tiles(11, 15, 400, 40, 880, 20, 40, .93, .86, z=1, blur=1.7, ph=0.6, accents=(9,))
            + sweep(355, 105, 2600, 1.5708, z=4)
            + '<div class="glow"></div>'
            + "".join(card(*c, z=6 + i * 2) for i, c in enumerate(CARDS))
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
