"""A magnified fragment of Android's own App info page: the greyed-out Permissions row reading
"No permissions requested", lifted out of the capture and floated beside the phone, in the
store frames' .frag language (rounded card, hairline, deep shadows). Static; it fades in with
the App info segment."""
import os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import layers as L

SRC = os.path.join(L.HERE, "out", "appinfo-frame.png")
CROP = (40, 1440, 508, 1580)          # the row, in 1080x1920 screen space
PAD, R = 26, 26
X, Y = 596, 725                       # card position on the 1920x1080 canvas (left of the phone, row height)

src = Image.open(SRC).convert("RGB").crop(CROP)
bg = src.getpixel((4, 4))
w, h = src.width + 2 * PAD, src.height + 2 * PAD
card = Image.new("RGBA", (w, h), bg + (255,))
card.paste(src, (PAD, PAD))
card.putalpha(L.rounded_mask(w, h, R))
layer = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 0))
for (oy, blur, alpha) in ((2, 3, 0.6), (22, 30, 0.58), (60, 80, 0.55)):
    sh = Image.new("L", (L.W, L.H), 0); sh.paste(L.rounded_mask(w, h, R), (X, Y + oy))
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    black = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 255))
    black.putalpha(Image.fromarray((np.asarray(sh, np.float32) * alpha).astype(np.uint8)))
    layer = Image.alpha_composite(layer, black)
full = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 0)); full.paste(card, (X, Y), card)
layer = Image.alpha_composite(layer, full)
# hairline, as .frag's outline: 1px of ink at 16%
ring = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 0))
ImageDraw.Draw(ring).rounded_rectangle([X, Y, X + w - 1, Y + h - 1], R, outline=(248, 239, 224, 40), width=1)
layer = Image.alpha_composite(layer, ring)
layer.save(os.path.join(L.OUT, "cap-frag-perms.png"))
print("card", w, "x", h, "bg", bg)
