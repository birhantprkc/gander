#!/usr/bin/env python3
"""Feature graphic, 1024x500 - type and two sharp pages. No blur anywhere.

The defocused-paper version read as filler: bokeh, big radial glows and a scrim over the
whole left side are the tells of a placeholder, not a considered image. Everything soft is
gone. What is left is a clean ground, a restrained type stack on a proper margin, and two
real pages rendered sharp, precisely offset, one running off the right edge.

The headline still carries the message on its own, because at 240px it is still the only
thing that survives - but the supporting element is now something made rather than
something smeared.

Rendered at 2x and downsampled.
"""
import math, pathlib

BUILD = pathlib.Path(__file__).parent
PAGES = BUILD / "pages"
W, H = 1024, 500


def page(x, y, w, src, rot=0.0, z=4, bright=1.0):
    """A rendered page, sharp, with a hairline edge and a tight shadow."""
    h = w * 1.5
    r = f"transform:rotate({rot}deg);" if rot else ""
    b = f"filter:brightness({bright});" if bright != 1.0 else ""
    return (f'<div class="pg" style="left:{x:.0f}px;top:{y:.0f}px;width:{w:.0f}px;'
            f'height:{h:.0f}px;z-index:{z};{r}{b}"><img src="{PAGES}/{src}"></div>')


def rule(base, amp, per, ph=0.0, z=3):
    """The family's terracotta curve, kept as a hairline. The glowing 38px version was
    half of what made the last pass look cheap."""
    pts = " L ".join(f"{x},{base + amp*math.sin(2*math.pi*(x/per)+ph):.1f}" for x in range(0, W + 1, 12))
    return f'''<svg style="position:absolute;left:0;top:0;z-index:{z};pointer-events:none"
      width="{W}" height="{H}"><defs>
      <linearGradient id="fd" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#F2795A" stop-opacity="0"/>
        <stop offset="24%" stop-color="#F2795A" stop-opacity=".55"/>
        <stop offset="60%" stop-color="#F2795A" stop-opacity=".70"/>
        <stop offset="100%" stop-color="#F2795A" stop-opacity="0"/></linearGradient></defs>
      <path d="M {pts}" fill="none" stroke="url(#fd)" stroke-width="1.6" stroke-linecap="round"/>
    </svg>'''


CSS = f"""
*{{box-sizing:border-box;margin:0}} body{{background:#000}}
.fg{{position:relative;width:{W}px;height:{H}px;overflow:hidden;font-family:Poppins,sans-serif;
  background:
    radial-gradient(620px 420px at 84% 46%, rgba(255,196,160,.075), transparent 68%),
    linear-gradient(103deg,#1A150E 0%,#141009 46%,#100C07 100%);}}
.pg{{position:absolute;overflow:hidden;border-radius:5px;background:#F4EFE5;
  box-shadow:0 0 0 1px rgba(248,239,224,.13),
             0 1px 2px rgba(0,0,0,.45), 0 22px 44px rgba(0,0,0,.50)}}
.pg img{{position:absolute;left:0;top:0;width:100%;display:block}}
.lock{{position:absolute;left:80px;top:78px;width:148px;z-index:9}}
.eyebrow{{position:absolute;left:82px;top:186px;z-index:9;color:#8E8474;font-size:12.5px;
  font-weight:600;letter-spacing:.28em;text-transform:uppercase}}
h1{{position:absolute;left:78px;top:214px;z-index:9;font-size:66px;font-weight:700;
  line-height:1.10;letter-spacing:-.038em;color:#F8EFE0}}
h1 .hot{{color:#F2795A}}
.sub{{position:absolute;left:82px;top:390px;z-index:9;color:#9C9080;font-size:16.5px;
  font-weight:500;letter-spacing:.014em}}
"""


def build(out="fg5.html"):
    body = (rule(452, 26, 1700, 0.5, z=3)
            + page(776, -8, 340, "ppt.png", z=4, bright=0.42)
            + page(802, -14, 358, "pdf.png", z=6, bright=0.88)
            + f'<img class="lock" src="{BUILD}/lockup.png">'
            + '<div class="eyebrow">Offline document viewer</div>'
            + '<h1>Opens everything.<br><span class="hot">Takes nothing.</span></h1>'
            + '<div class="sub">No permissions. No trackers. No internet access at all.</div>')
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
        f'<style>{CSS}</style></head><body><div class="fg">{body}</div></body></html>')
    return out


if __name__ == "__main__":
    print("wrote", build())
