"""Tile field for the panorama. Ordered, tonal, graduated - not scattered confetti."""
import math
BADGE=["#B3261E","#1565C0","#2E7D32","#B25000","#7B1FA2","#AD1457","#00838F","#455A64","#616161"]
GROUND=(0x16,0x11,0x0A)
CW,H=6480,1920

def mix(hexc, t):
    """Pull a badge colour t of the way toward the ground. t=0 full colour, t=1 invisible."""
    c=hexc.lstrip('#'); r,g,b=(int(c[i:i+2],16) for i in (0,2,4))
    m=lambda a,bb: round(a+(bb-a)*t)
    return f"#{m(r,GROUND[0]):02x}{m(g,GROUND[1]):02x}{m(b,GROUND[2]):02x}"

class R:
    def __init__(s,seed): s.x=seed
    def __call__(s,a=0.0,b=1.0):
        s.x=(1103515245*s.x+12345)%2147483648
        return a+(b-a)*(s.x/2147483648)

def wave(x, base, amp, period, phase=0.0):
    return base+amp*math.sin(2*math.pi*(x/period)+phase)

def procession(seed, n, base, amp, period, size_a, size_b, mute_a, mute_b,
               rot=6.0, z=6, blur=0.0, phase=0.0, jitter=34, accents=()):
    """Evenly spaced along the wave, size and mute graduating across the width.
    `accents` are indices kept at full colour - the few that carry the palette."""
    r=R(seed); out=[]
    for i in range(n):
        u=i/(n-1)
        x=-260+(CW+520)*u+r(-jitter,jitter)
        y=wave(x,base,amp,period,phase)+r(-jitter*0.9,jitter*0.9)
        sz=size_a+(size_b-size_a)*u+r(-14,14)
        t = mute_a+(mute_b-mute_a)*u
        c = BADGE[i%len(BADGE)]
        col = c if i in accents else mix(c, min(max(t,0),0.97))
        rt = r(-rot,rot)
        f=f"filter:blur({blur}px);" if blur else ""
        # a hairline inner edge is most of what separates a considered shape from a flat swatch
        edge = "box-shadow:inset 0 1px 0 rgba(255,255,255,.10);" if i in accents else ""
        out.append(f'<div style="position:absolute;left:{x:.0f}px;top:{y:.0f}px;width:{sz:.0f}px;'
                   f'height:{sz:.0f}px;background:{col};border-radius:{sz*0.235:.0f}px;'
                   f'transform:rotate({rt:.1f}deg);z-index:{z};{edge}{f}"></div>')
    return "".join(out)

def sweep(base, amp, period, phase=0.0, z=4):
    """A wide soft glow with a thin bright core, both fading out at the ends."""
    pts=" L ".join(f"{i},{wave(i,base,amp,period,phase):.1f}" for i in range(0,CW+1,60))
    return f'''<svg style="position:absolute;left:0;top:0;z-index:{z};pointer-events:none"
      width="{CW}" height="{H}">
      <defs>
        <linearGradient id="fade" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%"   stop-color="#E2795F" stop-opacity="0"/>
          <stop offset="18%"  stop-color="#E2795F" stop-opacity=".85"/>
          <stop offset="52%"  stop-color="#FF9E7A" stop-opacity="1"/>
          <stop offset="84%"  stop-color="#E2795F" stop-opacity=".8"/>
          <stop offset="100%" stop-color="#E2795F" stop-opacity="0"/>
        </linearGradient>
        <filter id="soft"><feGaussianBlur stdDeviation="26"/></filter>
      </defs>
      <path d="M {pts}" fill="none" stroke="url(#fade)" stroke-width="60"
            opacity=".28" filter="url(#soft)"/>
      <path d="M {pts}" fill="none" stroke="url(#fade)" stroke-width="5"
            stroke-linecap="round" opacity=".95"/>
    </svg>'''

def cards(seed, n, base, amp, period, w_a, w_b, op_a, op_b, rot=9.0, z=6,
          blur=0.0, phase=0.0, jitter=30, accents=()):
    """A procession of document cards - Gander's own social-preview motif.
    A card is a page with a format badge and a few lines of text on it."""
    r=R(seed); out=[]
    for i in range(n):
        u=i/(n-1)
        x=-300+(CW+600)*u+r(-jitter,jitter)
        y=wave(x,base,amp,period,phase)+r(-jitter,jitter)
        w=w_a+(w_b-w_a)*u+r(-10,10); h=w*1.30
        op=op_a+(op_b-op_a)*u
        rt=r(-rot,rot)
        c=BADGE[i%len(BADGE)]
        badge = c if i in accents else mix(c,.34)
        bw=w*0.30
        lines=""
        for k in range(4):
            lw = (0.74,0.60,0.68,0.44)[k]*w
            lines+=(f'<div style="position:absolute;left:{w*0.13:.0f}px;top:{h*0.545+k*h*0.098:.0f}px;'
                    f'width:{lw:.0f}px;height:{max(3,h*0.035):.0f}px;border-radius:3px;'
                    f'background:rgba(24,20,14,.20)"></div>')
        f=f"filter:blur({blur}px);" if blur else ""
        out.append(
          f'<div style="position:absolute;left:{x:.0f}px;top:{y:.0f}px;width:{w:.0f}px;height:{h:.0f}px;'
          f'background:linear-gradient(163deg,#FBF6EC,#EDE4D3);border-radius:{w*0.10:.0f}px;'
          f'opacity:{op:.2f};transform:rotate({rt:.1f}deg);z-index:{z};{f}'
          f'box-shadow:0 2px 3px rgba(0,0,0,.35),0 20px 44px rgba(0,0,0,.42)">'
          f'<div style="position:absolute;left:{w*0.13:.0f}px;top:{h*0.115:.0f}px;width:{bw:.0f}px;'
          f'height:{bw:.0f}px;border-radius:{bw*0.26:.0f}px;background:{badge}"></div>'
          f'{lines}</div>')
    return "".join(out)
