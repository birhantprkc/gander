#!/usr/bin/env python3
"""Feature graphic, 1024x500 - the claim, at the size it has to be read.

Three attempts argued about the picture. The size test settles it instead: at 240px, the
narrowest surface Play renders this on, nothing survives but the headline. Real document
pages are grey noise there; the nine labelled tiles survive but duplicate screenshot 1's
hero directly above them, and without the phone under them they read as a formats badge
row rather than the app. So the headline carries the whole message and everything else is
atmosphere.

The line is Arjun's own, from screenshots 1 and 2, put side by side for the first time:
breadth and the differentiator in four words. The category research found no document
viewer among 34 live listings making any privacy claim at all, so "Takes nothing." is the
half nobody else is saying.

Atmosphere is the panorama's own background - drawn document cards, thrown well out of
focus. It reads as a drift of paper without asking anyone to read one.

Rendered at 2x and downsampled.
"""
import math, pathlib

BUILD = pathlib.Path(__file__).parent
W, H = 1024, 500
BADGE = ["#B3261E", "#1565C0", "#2E7D32", "#B25000", "#7B1FA2", "#AD1457", "#00838F"]
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


def cards(seed, n, base, amp, per, w_a, w_b, op_a, op_b, blur_a, blur_b,
          x_a=-120, x_b=1160, rot=10.0, z=3, ph=0.0):
    """The panorama's paper-card motif, defocused. A card is a page with a format badge
    and a few ruled lines; at this blur only its shape and the badge's colour survive,
    which is the point - it says 'documents' without offering anything to read."""
    r = R(seed); out = ""
    for i in range(n):
        u = i / (n - 1)
        x = x_a + (x_b - x_a) * u + r(-34, 34)
        y = wave(x, base, amp, per, ph) + r(-38, 38)
        w = w_a + (w_b - w_a) * u + r(-14, 14); h = w * 1.3
        op = op_a + (op_b - op_a) * u
        bl = blur_a + (blur_b - blur_a) * u
        c = BADGE[i % len(BADGE)]
        bw = w * 0.30
        lines = "".join(
            f'<div style="position:absolute;left:{w*0.13:.0f}px;top:{h*0.545+k*h*0.098:.0f}px;'
            f'width:{(0.74,0.60,0.68,0.44)[k]*w:.0f}px;height:{max(3,h*0.035):.0f}px;'
            f'border-radius:3px;background:rgba(24,20,14,.22)"></div>' for k in range(4))
        out += (
          f'<div style="position:absolute;left:{x:.0f}px;top:{y:.0f}px;width:{w:.0f}px;height:{h:.0f}px;'
          f'background:linear-gradient(163deg,#FBF6EC,#EDE4D3);border-radius:{w*0.10:.0f}px;'
          f'opacity:{op:.3f};transform:rotate({r(-rot,rot):.1f}deg);z-index:{z};filter:blur({bl:.1f}px);'
          f'box-shadow:0 2px 3px rgba(0,0,0,.35),0 20px 44px rgba(0,0,0,.42)">'
          f'<div style="position:absolute;left:{w*0.13:.0f}px;top:{h*0.115:.0f}px;width:{bw:.0f}px;'
          f'height:{bw:.0f}px;border-radius:{bw*0.26:.0f}px;background:{mix(c,.22)}"></div>'
          f'{lines}</div>')
    return out


def sweep(base, amp, per, ph=0.0, z=5):
    pts = " L ".join(f"{x},{wave(x,base,amp,per,ph):.1f}" for x in range(0, W + 1, 12))
    return f'''<svg style="position:absolute;left:0;top:0;z-index:{z};pointer-events:none"
      width="{W}" height="{H}"><defs>
      <linearGradient id="fd" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#F2795A" stop-opacity="0"/>
        <stop offset="15%" stop-color="#F2795A" stop-opacity=".85"/>
        <stop offset="48%" stop-color="#FF9E7A" stop-opacity="1"/>
        <stop offset="85%" stop-color="#F2795A" stop-opacity=".8"/>
        <stop offset="100%" stop-color="#F2795A" stop-opacity="0"/></linearGradient>
      <filter id="sf"><feGaussianBlur stdDeviation="14"/></filter></defs>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="38" opacity=".30" filter="url(#sf)"/>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="3.2" stroke-linecap="round" opacity=".95"/>
    </svg>'''


CSS = f"""
*{{box-sizing:border-box;margin:0}} body{{background:#000}}
.fg{{position:relative;width:{W}px;height:{H}px;overflow:hidden;font-family:Poppins,sans-serif;
  background:
    radial-gradient(880px 540px at 10% -20%, rgba(242,121,90,.17), transparent 60%),
    radial-gradient(940px 560px at 88% 126%, rgba(120,150,210,.10), transparent 62%),
    linear-gradient(97deg,#1C160E 0%,#131009 40%,#181209 70%,#100C07 100%);}}
/* the type sits in its own pool of dark so the defocused paper never fights it */
.scrim{{position:absolute;left:0;top:0;width:74%;height:100%;z-index:6;pointer-events:none;
  background:linear-gradient(100deg, rgba(12,9,5,.90) 0%, rgba(12,9,5,.80) 42%,
             rgba(12,9,5,.42) 72%, transparent 100%)}}
.lock{{position:absolute;right:60px;top:52px;width:168px;z-index:9}}
.eyebrow{{position:absolute;left:66px;top:120px;z-index:9;color:#F2795A;font-size:15px;
  font-weight:700;letter-spacing:.24em;text-transform:uppercase}}
h1{{position:absolute;left:62px;top:156px;z-index:9;font-size:96px;font-weight:700;
  line-height:1.02;letter-spacing:-.045em;color:#F8EFE0;text-shadow:0 2px 26px rgba(0,0,0,.7)}}
h1 .hot{{color:#F2795A}}
.sub{{position:absolute;left:66px;top:380px;z-index:9;color:#9C9080;font-size:19px;
  font-weight:500;letter-spacing:.012em}}
.vig{{position:absolute;inset:0;z-index:12;pointer-events:none;
  background:
    radial-gradient(240px 150px at 88% 16%, rgba(10,8,4,.62), transparent 72%),
    linear-gradient(to right, transparent 72%, rgba(8,6,3,.40) 100%),
    linear-gradient(to bottom, rgba(0,0,0,.24), transparent 15%, transparent 84%, rgba(0,0,0,.34))}}
"""


def build(out="fg4.html"):
    body = (
      cards(19, 14, 244, 78, 1500, 92, 176, .12, .23, 7.5, 3.2, x_a=360, x_b=1080, z=3, ph=0.4)
      + cards(53, 9, 372, 58, 1180, 120, 214, .07, .15, 15.0, 8.0, x_a=300, x_b=1040, z=2, ph=1.5)
      + sweep(352, 74, 1560, 0.42, z=5)
      + '<div class="scrim"></div>'
      + f'<img class="lock" src="{BUILD}/lockup.png">'
      + '<div class="eyebrow">Offline document viewer</div>'
      + '<h1>Opens everything.<br><span class="hot">Takes nothing.</span></h1>'
      + '<div class="sub">No permissions. No trackers. No internet access at all.</div>'
      + '<div class="vig"></div>')
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
        f'<style>{CSS}</style></head><body><div class="fg">{body}</div></body></html>')
    return out


if __name__ == "__main__":
    print("wrote", build())
