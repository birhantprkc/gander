#!/usr/bin/env python3
"""Tablet panorama - one 10240x1600 canvas, sliced into four 2560x1600 Play frames.

Same construction as Concept C's phone panorama, for the same reason. The old
tab.py built four independent stages and rebuilt the bands from identical seeds
in each one, so all four frames carried the same background: one wallpaper with
four rectangles on it. Here the bands run the whole canvas, so no two frames
share a background, and each slate takes its height and its angle from the same
sine wave the bands ride - the phones already do this and it is most of why they
read as a set rather than a template.
"""
import math, pathlib

RAW = "/Users/arjun.maniyani/Desktop/Arjun/Projects/gander/docs/screenshots/v1.14-tab/raw"
PW, H, N = 2560, 1600, 4
CW = PW * N

# drift.py carries the tile/card/sweep vocabulary at phone-canvas size. Repoint its
# canvas constants rather than fork it, so the two sets keep one implementation.
import drift
drift.CW, drift.H = CW, H
from drift import procession, cards, sweep          # noqa: E402

# The wave every slate rides. Period is deliberately not a divisor of the canvas
# width, or panel 4 would land on panel 1's phase.
AMP, PER, PHZ = 105.0, 11000.0, 0.35
TILT = 1.30            # slope -> degrees, same trick as final_c.py's 0.62


def wave(x):
    return AMP * math.sin(2 * math.pi * (x / PER) + PHZ)


def tilt(x):
    slope = AMP * (2 * math.pi / PER) * math.cos(2 * math.pi * (x / PER) + PHZ)
    return -math.degrees(math.atan(slope)) * TILT


def slate(x, y, w, src, rot=0.0, z=12, patches=()):
    """A tablet: 16:10 screen, thin bezel, the rim light and shadow the phones use."""
    inner = w - 34
    sc = inner / 2560.0
    h = 1600 * sc + 34
    r = f"transform:rotate({rot:.2f}deg);" if rot else ""
    pt = "".join(
        f'<div style="position:absolute;left:{px*sc:.1f}px;top:{py*sc:.1f}px;'
        f'width:{pw*sc:.1f}px;height:{ph*sc:.1f}px;background:{pc}"></div>'
        for (px, py, pw, ph, pc) in patches)
    return (f'<div class="slate" style="left:{x:.0f}px;top:{y:.0f}px;width:{w:.0f}px;'
            f'height:{h:.0f}px;z-index:{z};{r}">'
            f'<div class="scr"><img src="{RAW}/{src}">{pt}<div class="sheen"></div></div></div>')


def glow(x, y, w, h, z=3):
    return (f'<div class="glow" style="left:{x:.0f}px;top:{y:.0f}px;'
            f'width:{w:.0f}px;height:{h:.0f}px;z-index:{z}"></div>')


def fragment(x, y, w, src, cx, cy, cw, ch, rot=0.0, z=16):
    """A control lifted out of the same capture and magnified, in front of the slate."""
    sc = w / float(cw)
    r = f"transform:rotate({rot:.2f}deg);" if rot else ""
    return (f'<div class="frag" style="left:{x:.0f}px;top:{y:.0f}px;width:{w:.0f}px;'
            f'height:{ch*sc:.0f}px;z-index:{z};{r}">'
            f'<img src="{RAW}/{src}" style="width:{2560*sc:.1f}px;'
            f'transform:translate({-cx*sc:.1f}px,{-cy*sc:.1f}px)"></div>')


def cap(a, hot, kick, x, y, align="left", w=1500):
    """Headline block, in panel-local coordinates."""
    ta = "right" if align == "right" else "left"
    return (f'<div class="head" style="left:{x}px;top:{y}px;width:{w}px;text-align:{ta}">'
            f'<h1>{a}<br><span class="hot">{hot}</span></h1>'
            f'<p class="kick">{kick}</p></div>')


CSS = f"""
:root{{--ink:#F8EFE0;--hot:#E2795F;--dim:#CFC5B2}}
*{{box-sizing:border-box;margin:0}}
body{{margin:0;background:#000}}
.canvas{{position:relative;width:{CW}px;height:{H}px;overflow:hidden;font-family:Jost,sans-serif;
  color:var(--ink);
  background:
    radial-gradient(3000px 1500px at 9% -12%, rgba(226,121,95,.11), transparent 62%),
    radial-gradient(3200px 1600px at 74% 122%, rgba(120,150,210,.075), transparent 62%),
    linear-gradient(97deg,#1A140D 0%,#131009 28%,#191309 55%,#120E08 80%,#0E0B06 100%);}}
/* warm light pooled under each capture, so a slate sits in something rather than on nothing */
.glow{{position:absolute;pointer-events:none;
  background:radial-gradient(closest-side, rgba(255,176,140,.16), transparent 72%)}}
.vig{{position:absolute;left:0;top:0;width:{CW}px;height:{H}px;z-index:22;pointer-events:none;
  background:linear-gradient(to bottom, rgba(0,0,0,.30) 0%, transparent 14%,
             transparent 78%, rgba(0,0,0,.40) 100%)}}
.pan{{position:absolute;top:0;width:{PW}px;height:{H}px;overflow:hidden;z-index:12}}
.head{{position:absolute;z-index:1}}
h1{{font-size:178px;font-weight:700;line-height:.92;letter-spacing:-.045em;
  text-shadow:0 2px 34px rgba(0,0,0,.6)}}
h1 .hot{{color:var(--hot)}}
.kick{{font-size:46px;font-weight:500;color:var(--dim);margin-top:40px;line-height:1.3}}
.slate{{position:absolute;border-radius:46px;padding:17px;
  background:linear-gradient(155deg,#4A4238 0%,#1A160F 26%,#0E0C08 58%,#2C2620 100%);
  box-shadow:
    0 0 0 1px rgba(255,255,255,.07),
    0 4px 8px rgba(0,0,0,.55),
    0 42px 84px rgba(0,0,0,.55),
    0 120px 210px rgba(0,0,0,.6)}}
.slate .scr{{position:relative;width:100%;height:100%;border-radius:31px;overflow:hidden;
  background:#0A0806;box-shadow:inset 0 0 0 1px rgba(0,0,0,.85)}}
.slate .scr img{{position:absolute;left:0;top:0;width:100%;display:block}}
.slate .sheen{{position:absolute;inset:0;border-radius:31px;pointer-events:none;
  background:linear-gradient(122deg, rgba(255,255,255,.10) 0%, rgba(255,255,255,.03) 18%,
             transparent 36%)}}
.frag{{position:absolute;overflow:hidden;border-radius:34px;background:#191410;
  outline:1px solid rgba(248,239,224,.17);outline-offset:-1px;
  box-shadow:0 3px 6px rgba(0,0,0,.6),0 34px 66px rgba(0,0,0,.58),0 90px 165px rgba(0,0,0,.55)}}
.frag img{{position:absolute;left:0;top:0;display:block;transform-origin:0 0}}
"""


def build(panels, bands, out):
    # glow stays on the canvas under the card bands; caption and device are clipped
    # into the panel so nothing of one frame appears in the next.
    parts = list(bands)
    for i, p in enumerate(panels):
        parts.append(p["glow"])
    for i, p in enumerate(panels):
        parts.append(f'<div class="pan" style="left:{i*PW}px">'
                     + cap(*p["cap"], p["kick"], *p["head"]) + p["fore"] + '</div>')
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Jost:wght@400;500;600;700&display=block" rel="stylesheet">'
        f'<style>{CSS}</style></head><body><div class="canvas">'
        + "".join(parts) + '<div class="vig"></div></div></body></html>')
    return len(panels)
