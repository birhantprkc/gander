#!/usr/bin/env python3
"""Concept B - "Night". Dark, full-bleed, poster-loud.

Same rule as Concept A: every pixel of app UI is a real capture.
HTML only crops, scales, tilts and captions.
"""
import pathlib
RAW = "/Users/arjun.maniyani/Desktop/Arjun/Projects/gander/docs/screenshots/v1.14/raw"

CSS = """
:root{--bg:#141009;--bg2:#221B10;--ink:#F8EFE0;--hot:#F2795A;--dim:#9A8E7A}
*{box-sizing:border-box;margin:0}
body{background:#555}
.stage{width:1080px;height:1920px;overflow:hidden;position:relative;
 background:radial-gradient(120% 80% at 50% 0%, #241D12 0%, #141009 62%);
 font-family:Poppins,sans-serif;color:var(--ink)}
/* a heavy slab of accent, the thing that reads first at 166px */
.blk{position:absolute;background:var(--hot);border-radius:34px}
.head{position:absolute;left:76px;right:76px;top:150px;z-index:6}
h1{font-size:172px;font-weight:700;line-height:.92;letter-spacing:-.045em}
h1 .hot{color:var(--hot)}
.kick{font-size:46px;font-weight:500;color:var(--dim);margin-top:38px;line-height:1.3;
      letter-spacing:.005em}
.win{position:absolute;overflow:hidden}
.win img{position:absolute;left:0;top:0;width:1080px;display:block;transform-origin:0 0}
.soft{border-radius:34px;box-shadow:0 30px 80px rgba(0,0,0,.7)}
.ring{border:1px solid rgba(248,239,224,.14)}
"""

def win(cls,x,y,w,h,src,cx,cy,s,rot=0,extra=""):
    tx,ty=-cx*s,-cy*s
    r=f"transform:rotate({rot}deg);" if rot else ""
    return (f'<div class="win {cls}" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;{r}{extra}">'
            f'<img src="{RAW}/{src}" style="transform:translate({tx:.1f}px,{ty:.1f}px) scale({s})"></div>')

def build(panels,out):
    b=[]
    for p in panels:
        a,hot = p["cap"]
        kick = f'<p class="kick">{p["kick"]}</p>' if p.get("kick") else ""
        b.append('<div class="stage">'
                 f'<div class="head"><h1>{a}<br><span class="hot">{hot}</span></h1>{kick}</div>'
                 + "".join(p["parts"]) + "</div>")
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
        f"<style>{CSS}</style></head><body>" + "\n".join(b) + "</body></html>")
    return len(panels)
