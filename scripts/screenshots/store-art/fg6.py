#!/usr/bin/env python3
"""Feature graphic, 1024x500 - flat colour, one hairline, an 8px grid.

Rebuilt against the craft rules after four passes that all failed the same way. The
previous file broke four of them at once: the ground was two gradients plus a warm radial
glow (R1, R2), the pages carried a hairline ring AND a 0 22px 44px rgba(0,0,0,.50) shadow
(R10), and every coordinate was eyeballed - left 78/80/82, top 78/186/214/390 (R8).
Gradients and glow are what "cheap" looks like; they are emphasis borrowed rather than
earned.

So: one flat warm near-black, no gradient anywhere, no shadow anywhere. Structure comes
from a single hairline and from the grid. Every value is a multiple of 8. Emphasis comes
from size, weight, colour and space, which is the only way it is supposed to come.

Rendered at 2x and downsampled.
"""
import pathlib

BUILD = pathlib.Path(__file__).parent
PAGES = BUILD / "pages"
W, H = 1024, 500

# One neutral ramp, warm, no pure black and no pure white (R7).
GROUND = "#15110C"
INK    = "#F5EEE1"
MUTED  = "#8F8676"
HAIR   = "rgba(245,238,225,.13)"
CORAL  = "#F2795A"

# 8px scale (R8). Margin 64 = 8x8; the type stack, the rule and the page all land on it.
M      = 64
STACK  = {"lock": 80, "accent": 160, "eyebrow": 184, "h1": 216, "sub": 392}
DIVIDE = 672
PAGE   = (704, 80, 256, 384)          # x, y, w, h - h/w is the 1.5 page ratio

CSS = f"""
*{{box-sizing:border-box;margin:0}} body{{background:#000}}
.fg{{position:relative;width:{W}px;height:{H}px;overflow:hidden;isolation:isolate;
  font-family:Poppins,sans-serif;background:{GROUND}}}

/* structure is one hairline, not a shadow and not a fade (R1, R10) */
.divide{{position:absolute;left:{DIVIDE}px;top:0;width:1px;height:{H}px;
  background:{HAIR};z-index:1}}
.page{{position:absolute;left:{PAGE[0]}px;top:{PAGE[1]}px;width:{PAGE[2]}px;height:{PAGE[3]}px;
  overflow:hidden;background:#F4EFE5;z-index:2}}
.page img{{position:absolute;left:0;top:0;width:100%;display:block}}

/* one flat mark of brand colour, used once (R1) */
.accent{{position:absolute;left:{M}px;top:{STACK['accent']}px;width:48px;height:3px;
  background:{CORAL};z-index:3}}

/* four type sizes; hierarchy from weight and colour before size (R9) */
.lock{{position:absolute;left:{M}px;top:{STACK['lock']}px;width:128px;z-index:3}}
.eyebrow{{position:absolute;left:{M}px;top:{STACK['eyebrow']}px;z-index:3;color:{MUTED};
  font-size:13px;font-weight:600;letter-spacing:.24em;text-transform:uppercase;line-height:1.2}}
h1{{position:absolute;left:{M}px;top:{STACK['h1']}px;z-index:3;color:{INK};
  font-size:64px;font-weight:700;line-height:1.125;letter-spacing:-.035em}}
h1 .hot{{color:{CORAL}}}
.sub{{position:absolute;left:{M}px;top:{STACK['sub']}px;z-index:3;color:{MUTED};
  font-size:17px;font-weight:500;line-height:1.5;letter-spacing:.01em}}
"""


def build(out="fg6.html"):
    body = ('<div class="divide"></div>'
            f'<div class="page"><img src="{PAGES}/doc.png"></div>'
            f'<img class="lock" src="{BUILD}/lockup.png">'
            '<div class="accent"></div>'
            '<div class="eyebrow">Offline document viewer</div>'
            '<h1>Opens everything.<br><span class="hot">Takes nothing.</span></h1>'
            '<div class="sub">No permissions. No trackers. No internet access at all.</div>')
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
        f'<style>{CSS}</style></head><body><div class="fg">{body}</div></body></html>')
    return out


if __name__ == "__main__":
    print("wrote", build())
