#!/usr/bin/env python3
"""Lay out real Gander captures into 1080x1920 Play frames.

Every pixel of app UI here is a real device capture. HTML only crops,
scales, positions and captions it.
"""
import os, sys, json, subprocess, pathlib

W, H = 1080, 1920
RAW = "/Users/arjun.maniyani/Desktop/Arjun/Projects/gander/docs/screenshots/v1.14/raw"

CSS = """
:root{--navy:#182a45;--red:#c23a20;--pt:#f4eee4;--pb:#e9dfcc;--rule:rgba(120,96,50,.075);
      --ink2:#4D4636;--edge:#CFC5B2}
*{box-sizing:border-box;margin:0}
body{background:#8d8d8d}
.stage{width:1080px;height:1920px;overflow:hidden;position:relative;
 background:repeating-linear-gradient(to bottom,transparent 0 47px,var(--rule) 47px 48px),
            linear-gradient(to bottom,var(--pt),var(--pb));
 font-family:Poppins,sans-serif;color:var(--navy)}
.stage.dark{background:linear-gradient(to bottom,#17130A,#231D12);color:#F8F3EA}
.stage.dark .sub{color:#CFC5B2}
.head{position:absolute;left:84px;top:118px;right:84px;z-index:5}
.tick{width:96px;height:10px;background:var(--red);border-radius:5px;margin-bottom:44px}
h1{font-size:118px;line-height:1.0;letter-spacing:-.03em}
h1 .a{font-weight:400;display:block}
h1 .b{font-weight:700;display:block}
.sub{font-size:82px;font-weight:500;line-height:1.24;color:var(--ink2);margin-top:44px}

/* a cropped region of a real capture */
.win{position:absolute;overflow:hidden;background:#FEF9F0;
     border:1px solid var(--edge);border-radius:38px;
     box-shadow:0 10px 26px rgba(24,42,69,.12),0 44px 90px rgba(24,42,69,.22)}
.win.plain{border-radius:0;border:none;box-shadow:none;background:transparent}
.win img{position:absolute;left:0;top:0;width:1080px;display:block;transform-origin:0 0;
         image-rendering:-webkit-optimize-contrast}
/* the lifted fragment sits in front of the main window */
.lift{z-index:4;box-shadow:0 16px 40px rgba(24,42,69,.20),0 60px 110px rgba(24,42,69,.26)}
.main{z-index:2}
.poster{position:absolute;left:84px;right:84px;top:640px;z-index:3}
.mb{font-size:430px;font-weight:700;line-height:.8;letter-spacing:-.05em;display:flex;align-items:flex-end;gap:26px}
.mb span{font-size:160px;font-weight:600}
.zrow{display:flex;align-items:center;gap:40px;margin-bottom:34px}
.zn{font-size:196px;font-weight:700;line-height:.8;width:176px;letter-spacing:-.045em}
.zl{font-size:72px;font-weight:500}
"""

def win(cls, x, y, w, h, src, cx, cy, s, rot=0, extra=""):
    tx, ty = -cx * s, -cy * s
    r = f"transform:rotate({rot}deg);" if rot else ""
    return (f'<div class="win {cls}" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;{r}{extra}">'
            f'<img src="{RAW}/{src}" style="transform:translate({tx:.1f}px,{ty:.1f}px) scale({s})"></div>')

def head(a, b, sub=None):
    s = f'<p class="sub">{sub}</p>' if sub else ""
    bb = f'<span class="b">{b}</span>' if b else ""
    return f'<div class="head"><div class="tick"></div><h1><span class="a">{a}</span>{bb}</h1>{s}</div>'

def build(panels, out):
    body = []
    for p in panels:
        cls = "stage dark" if p.get("dark") else "stage"
        body.append(f'<div class="{cls}">' + head(*p["cap"], p.get("sub")) + "".join(p["parts"]) + "</div>")
    html = ('<!doctype html><html><head><meta charset="utf-8">'
            '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
            f"<style>{CSS}</style></head><body>" + "\n".join(body) + "</body></html>")
    pathlib.Path(out).write_text(html)
    return len(panels)
