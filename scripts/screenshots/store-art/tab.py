#!/usr/bin/env python3
"""Tablet panels: same language as Concept C, laid out for a 2560x1600 landscape frame.

Caption on the left, the device on the right, the sweep and the card drift crossing
underneath - so the tablet set and the phone set read as one listing.
"""
import math, pathlib
RAW="/Users/arjun.maniyani/Desktop/Arjun/Projects/gander/docs/screenshots/v1.14-tab/raw"
W,H = 2560,1600
BADGE=["#B3261E","#1565C0","#2E7D32","#B25000","#7B1FA2","#AD1457","#00838F","#455A64","#616161"]
G=(0x16,0x11,0x0A)
def mix(h,t):
    c=h.lstrip('#'); r,g,b=(int(c[i:i+2],16) for i in (0,2,4))
    m=lambda a,bb: round(a+(bb-a)*t); return f"#{m(r,G[0]):02x}{m(g,G[1]):02x}{m(b,G[2]):02x}"
class R:
    def __init__(s,seed): s.x=seed
    def __call__(s,a=0.,b=1.):
        s.x=(1103515245*s.x+12345)%2147483648; return a+(b-a)*(s.x/2147483648)
def wave(x,base,amp,per,ph=0.): return base+amp*math.sin(2*math.pi*(x/per)+ph)

def sweep(base,amp,per,ph=0.,z=4):
    pts=" L ".join(f"{x},{wave(x,base,amp,per,ph):.1f}" for x in range(0,W+1,40))
    return f'''<svg style="position:absolute;left:0;top:0;z-index:{z}" width="{W}" height="{H}">
     <defs><linearGradient id="f" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#F2795A" stop-opacity="0"/><stop offset="20%" stop-color="#F2795A" stop-opacity=".8"/>
      <stop offset="55%" stop-color="#FF9E7A" stop-opacity="1"/><stop offset="86%" stop-color="#F2795A" stop-opacity=".65"/>
      <stop offset="100%" stop-color="#F2795A" stop-opacity="0"/></linearGradient>
      <filter id="s"><feGaussianBlur stdDeviation="22"/></filter></defs>
     <path d="M {pts}" fill="none" stroke="url(#f)" stroke-width="52" opacity=".28" filter="url(#s)"/>
     <path d="M {pts}" fill="none" stroke="url(#f)" stroke-width="4" stroke-linecap="round" opacity=".92"/></svg>'''

def drift(seed,n,base,amp,per,sa,sb,ma,mb,rot=8,z=6,blur=0.,ph=0.,accents=()):
    r=R(seed); out=""
    for i in range(n):
        u=i/(n-1); x=-200+(W+400)*u+r(-40,40); y=wave(x,base,amp,per,ph)+r(-50,50)
        sz=sa+(sb-sa)*u+r(-12,12); col=BADGE[i%9] if i in accents else mix(BADGE[i%9],ma+(mb-ma)*u)
        f=f"filter:blur({blur}px);" if blur else ""
        out+=(f'<div style="position:absolute;left:{x:.0f}px;top:{y:.0f}px;width:{sz:.0f}px;height:{sz:.0f}px;'
              f'background:{col};border-radius:{sz*.235:.0f}px;transform:rotate({r(-rot,rot):.1f}deg);'
              f'z-index:{z};{f}box-shadow:0 12px 28px rgba(0,0,0,.45)"></div>')
    return out

def slate(x,y,w,src,rot=0.,z=12):
    """A tablet: 16:10 screen, thin bezel, same rim light and shadow as the phones."""
    inner=w-34; h=inner*(1600/2560.)+34
    r=f"transform:rotate({rot}deg);" if rot else ""
    return (f'<div class="slate" style="left:{x}px;top:{y}px;width:{w}px;height:{h:.0f}px;z-index:{z};{r}">'
            f'<div class="scr"><img src="{RAW}/{src}"><div class="sheen"></div></div></div>')

CSS=f"""
:root{{--ink:#F8EFE0;--hot:#F2795A;--dim:#9C9080}}
*{{box-sizing:border-box;margin:0}} body{{background:#555}}
.stage{{position:relative;width:{W}px;height:{H}px;overflow:hidden;font-family:Poppins,sans-serif;color:var(--ink);
 background:radial-gradient(1600px 900px at 6% -8%, rgba(242,121,90,.13), transparent 62%),
   radial-gradient(1700px 1000px at 92% 120%, rgba(120,150,210,.09), transparent 62%),
   linear-gradient(97deg,#1B150E 0%,#131009 40%,#181209 70%,#100C07 100%);}}
.head{{position:absolute;top:430px;width:940px;z-index:8}}
h1{{font-size:150px;font-weight:700;line-height:.94;letter-spacing:-.042em;text-shadow:0 2px 30px rgba(0,0,0,.55)}}
h1 .hot{{color:var(--hot)}}
.kick{{font-size:42px;font-weight:500;color:var(--dim);margin-top:38px;line-height:1.32}}
.slate{{position:absolute;border-radius:44px;padding:17px;
 background:linear-gradient(155deg,#4A4238 0%,#1A160F 26%,#0E0C08 58%,#2C2620 100%);
 box-shadow:0 0 0 1px rgba(255,255,255,.07),0 4px 8px rgba(0,0,0,.55),
   0 40px 80px rgba(0,0,0,.55),0 110px 200px rgba(0,0,0,.6)}}
.slate .scr{{position:relative;width:100%;height:100%;border-radius:30px;overflow:hidden;background:#0A0806}}
.slate .scr img{{position:absolute;left:0;top:0;width:100%;display:block}}
.frag{{position:absolute;overflow:hidden;border-radius:30px;background:#191410;
 outline:1px solid rgba(248,239,224,.17);outline-offset:-1px;
 box-shadow:0 3px 6px rgba(0,0,0,.6),0 30px 60px rgba(0,0,0,.58),0 80px 150px rgba(0,0,0,.55)}}
.frag img{{position:absolute;left:0;top:0;display:block;transform-origin:0 0}}
.slate .sheen{{position:absolute;inset:0;border-radius:30px;pointer-events:none;
 background:linear-gradient(122deg, rgba(255,255,255,.10) 0%, rgba(255,255,255,.03) 18%, transparent 36%)}}
.vig{{position:absolute;inset:0;z-index:20;pointer-events:none;
 background:linear-gradient(to bottom, rgba(0,0,0,.30), transparent 15%, transparent 80%, rgba(0,0,0,.36))}}
"""

def fragment(x,y,w,src,cx,cy,cw,ch,rot=0.,z=16):
    """A control lifted out of the same capture and magnified, in front of the slate."""
    sc=w/float(cw); h=ch*sc
    r=f"transform:rotate({rot}deg);" if rot else ""
    return (f'<div class="frag" style="left:{x}px;top:{y}px;width:{w}px;height:{h:.0f}px;z-index:{z};{r}">'
            f'<img src="{RAW}/{src}" style="width:{2560*sc:.1f}px;'
            f'transform:translate({-cx*sc:.1f}px,{-cy*sc:.1f}px)"></div>')

def build(panels,out):
    b=[]
    for p in panels:
        a,hot=p["cap"]
        hx=p.get("head_x",150); hy=p.get("head_y",430)
        b.append(f'<div class="stage">{p["bands"]}'
                 f'<div class="head" style="left:{hx}px;top:{hy}px"><h1>{a}<br><span class="hot">{hot}</span></h1>'
                 f'<p class="kick">{p["kick"]}</p></div>{p["parts"]}<div class="vig"></div></div>')
    pathlib.Path(out).write_text(
      '<!doctype html><html><head><meta charset="utf-8">'
      '<link href="https://fonts.googleapis.com/css2?family=Poppins:wght@400;500;600;700&display=block" rel="stylesheet">'
      f"<style>{CSS}</style></head><body>"+"\n".join(b)+"</body></html>")
    return len(panels)
