#!/usr/bin/env python3
"""Feature graphic, 1024x500 - fg2's composition, built clean.

Same picture: the panorama's `drift.cards()` motif with the drawing removed, four pages
Gander actually rendered, stacked and growing left to right, each tagged at its foot with
the app's own format chip. Nothing about the layout changes.

What changes is everything underneath it. fg2 carried three gradients on the ground, a
radial glow pool, a gradient vignette over the whole frame, a raking gradient highlight on
every card, a gradient-stroked blurred sweep, blurred tiles behind, text-shadows on both
type blocks, and an outline stacked on three shadows at rgba(0,0,0,.5). Fourteen soft
effects doing the work that colour, size and spacing should do. All of them are gone:

  page scale whole page shrunk 4x -> a 1.75x window, so the text is legible
  ground     3 gradients -> one flat warm near-black
  glow pool  radial-gradient -> deleted
  vignette   linear-gradient -> deleted
  card sheen linear-gradient -> deleted
  sweep      gradient stroke + 9px blur -> deleted
  tile band  15 blurred squares -> deleted
  card edge  outline + 3 shadows at .5 -> one layered shadow, no outline
  chip       inset white gradient -> flat colour
  type       two text-shadows -> none
  blur       0.7 and 0.4 on the far cards -> none; depth is size and brightness

Every coordinate is a multiple of 8. Rendered at 4x and downsampled with Lanczos.

On the page scale: a whole 1080px page shrunk into a 280px card puts 14px body text at
3.6px, below the resolution floor - it smears no matter how the render is done, and that
is what "the resolution looks bad" was pointing at. A 1.75x window onto each document
puts the same text at 6.5px and it reads. The cards stop being whole pages and become
windows onto documents, which for a document viewer is the better claim anyway.
"""
import pathlib

BUILD = pathlib.Path(__file__).parent
PAGES = BUILD / "pages"
W, H = 1024, 500

GROUND = "#17130C"      # one flat warm near-black; no pure black anywhere
INK    = "#F8EFE0"
MUTED  = "#CFC5B2"
CORAL  = "#E2795F"
PAPER  = "#EFE9DD"
BADGE  = {"XLS": "#2E7D32", "PPT": "#B25000", "IMG": "#7B1FA2", "PDF": "#B3261E"}

# x, width, centre-y, source, format, rotation, brightness. All on the 8px grid.
# Depth is carried by size, overlap and brightness - the three cues that survive being
# looked at small. Blur was carrying it before, and blur is what read as cheap.
CARDS = [
    (480, 168, 320, "xls.png", "XLS", -3.0, 0.66),
    (608, 200, 304, "ppt.png", "PPT", -1.0, 0.78),
    (736, 232, 288, "img.png", "IMG",  1.0, 0.90),
    (864, 280, 248, "pdf.png", "PDF",  3.0, 1.00),
]

STACK = {"lock": 96, "kicker": 184, "hero": 240, "rule": 368, "sub": 392,
         "fmt": 428}
M = 64


ZOOM = 1.75      # document scale inside the card; 1.0 fits the whole page and smears it
PAN  = 0.00      # how far left the window sits, as a fraction of card width


def card(x, w, cy, src, fmt, rot, bright, z):
    h = w * 1.5
    top = cy - h / 2
    bw = round(w * 0.17 / 2) * 2          # keep the chip on an even pixel
    return (
      f'<div class="card" style="left:{x}px;top:{top:.0f}px;width:{w}px;height:{h:.0f}px;'
      f'z-index:{z};transform:rotate({rot}deg);filter:brightness({bright})">'
      f'<img src="{PAGES}/{src}" style="width:{w*ZOOM:.0f}px;margin-left:{-w*PAN:.0f}px"></div>'
      f'<div class="chip" style="left:{x-bw*0.38:.0f}px;top:{top+h-bw:.0f}px;width:{bw}px;'
      f'height:{bw}px;background:{BADGE[fmt]};border-radius:{bw*0.235:.0f}px;z-index:{z+1};'
      f'transform:rotate({rot}deg);'
      f'font-size:{bw*0.34:.0f}px;filter:brightness({min(1.0, bright + 0.10):.2f})">{fmt}</div>')


CSS = f"""
*{{box-sizing:border-box;margin:0}} body{{background:#000}}
.fg{{position:relative;width:{W}px;height:{H}px;overflow:hidden;isolation:isolate;
  font-family:Jost,sans-serif;background:{GROUND}}}

/* one elevation language: these float, so they get a soft layered shadow and no outline.
   A border and a heavy shadow on the same element is what made them look pasted on. */
.card,.chip{{position:absolute;
  box-shadow:0 1px 2px rgba(0,0,0,.30),0 10px 20px rgba(0,0,0,.30),0 30px 60px rgba(0,0,0,.26)}}
.card{{overflow:hidden;border-radius:8px;background:{PAPER}}}
.card img{{position:absolute;left:0;top:0;width:100%;display:block}}
.chip{{display:flex;align-items:center;justify-content:center;color:#fff;
  font-weight:700;letter-spacing:.02em}}

/* three type sizes; the jump from 44 to 88 does the hierarchy, not a shadow */
.lock{{position:absolute;left:{M}px;top:{STACK['lock']}px;width:160px;z-index:8}}
.kicker{{position:absolute;left:{M}px;top:{STACK['kicker']}px;z-index:8;color:{INK};
  font-size:44px;font-weight:500;letter-spacing:-.02em;line-height:1.2}}
.hero{{position:absolute;left:{M}px;top:{STACK['hero']}px;z-index:8;color:{CORAL};
  font-size:96px;font-weight:700;letter-spacing:-.048em;line-height:.94}}
/* a hairline gives the empty half structure; a fade would give it murk */
.rule{{position:absolute;left:{M}px;top:{STACK['rule']}px;width:360px;height:1px;
  background:rgba(248,239,224,.15);z-index:8}}
.sub{{position:absolute;left:{M}px;top:{STACK['sub']}px;z-index:8;color:{MUTED};
  font-size:17px;font-weight:500;letter-spacing:.015em;line-height:1.5}}
.sub i{{color:{CORAL};font-style:normal;padding:0 2px}}
/* the breadth claim, spelled out - real content, and it gives the empty lower left a
   third typographic level instead of leaving it as a black band */
.fmt{{position:absolute;left:{M}px;top:{STACK['fmt']}px;z-index:8;color:#6F6758;
  font-size:12.5px;font-weight:500;letter-spacing:.05em;line-height:1.55}}
"""


def build(out="fg7.html"):
    body = ("".join(card(*c, z=2 + i * 2) for i, c in enumerate(CARDS))
            + f'<img class="lock" src="{BUILD}/lockup.png">'
            + '<div class="kicker">Take a gander at</div>'
            + '<div class="hero">any file.</div>'
            + '<div class="rule"></div>'
            + '<div class="sub">Offline viewer <i>&middot;</i> no permissions <i>&middot;</i> no trackers</div>'
            + '<div class="fmt">PDF &middot; Word &middot; Excel &middot; PowerPoint &middot; Photos<br>'
              'Video &middot; Audio &middot; Markdown &middot; Code</div>')
    pathlib.Path(out).write_text(
        '<!doctype html><html><head><meta charset="utf-8">'
        '<link href="https://fonts.googleapis.com/css2?family=Jost:wght@400;500;600;700&display=block" rel="stylesheet">'
        f'<style>{CSS}</style></head><body><div class="fg">{body}</div></body></html>')
    return out


if __name__ == "__main__":
    print("wrote", build())
