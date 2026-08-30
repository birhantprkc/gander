import sys; sys.path.insert(0, '.')
from tabpano import build, slate, fragment, glow, wave, tilt, PW
from drift import procession, cards, sweep

# No lift on any frame. The slate shows the whole welcome screen, so anything lifted off it
# is also on it - the tile grid was appearing twice. Cropping the slate to hide the source
# does not help either: the welcome screen is one centred column, so hiding the tiles
# hides the wordmark and the privacy line with them.


def panel(i, cap, kick, head, sl, frag=None, glow_pad=(260, 320)):
    """One frame. `sl` is (x_in_panel, width, y_base, src); the height and the angle
    come from the wave at the slate's own centre on the canvas, so no two slates sit
    at the same height or lean at the same angle."""
    x, w, ybase, src = sl
    cx = i * PW + x + w / 2
    y = ybase + wave(cx)
    rot = tilt(cx)
    h = (w - 34) * 0.625 + 34
    gx, gy = glow_pad
    fore = slate(x, y, w, src, rot=rot, z=12)
    if frag:
        fx, fy, fw, box, frot = frag
        fore += fragment(fx, fy, fw, src, *box, rot=rot + frot, z=16)
    return {"cap": cap, "kick": kick, "head": head, "fore": fore,
            "glow": glow(i * PW + x - gx, y - gy, w + 2 * gx, h + 2 * gy)}


panels = [
    # 1  the whole welcome screen, once. Its content is a centred column with ~900 raw px
    #    of empty screen either side, so a landscape frame always carries one of those
    #    margins unless the type sits on the device; the slate is sized so the privacy
    #    line and both buttons clear the right edge rather than crowd it.
    panel(0, ("Opens", "everything."),
          "Nine kinds of file, and nothing<br>uploaded to open any of them.",
          (150, 215, "left", 1450),
          (1250, 1960, 95, "t-welcome.png")),

    # 2  type across the top, device entering from the bottom - the only frame that
    #    shows the full width, because the two-column layout is its whole argument.
    #    Placed low so the content band lands in frame and the empty lower two-thirds
    #    of the file browser runs off the edge instead of sitting in the picture.
    panel(1, ("Room to", "spread out."),
          "Two columns on a large screen.",
          (150, 340, "left", 1800),
          (120, 2320, 960, "t-folder.png")),

    # 3  mirrored: caption right, device running off the left. The bleed clears the
    #    status clock and stops short of raw x=260, where the label column starts.
    panel(2, ("Reads", "anything."),
          "Every document renders on the device.<br>No account, no upload, no cloud.",
          (1460, 440, "right", 960),
          (-140, 1560, 500, "t-pdf.png")),

    # 4  the densest capture, largest slate, caption dropped low - a sheet running off
    #    the right edge reads as more columns, which is the claim
    panel(3, ("Every", "sheet."),
          "Multi-sheet workbooks, tabs and all.<br>xlsx, xls, xlsm, xlsb, csv, ods.",
          (150, 470, "left", 1250),
          (1180, 2200, 340, "t-xlsx.png")),
]

bands = [
    procession(seed=7, n=20, base=670, amp=112, period=4900,
               size_a=128, size_b=200, mute_a=.92, mute_b=.84, rot=9, z=1, blur=2.6,
               phase=0.7, jitter=70),
    sweep(base=1250, amp=110, period=8200, phase=0.35, z=4),
    cards(seed=41, n=17, base=1300, amp=112, period=8200,
          w_a=255, w_b=364, op_a=.30, op_b=.52, rot=8, z=6, blur=1.3, phase=0.35,
          jitter=48, accents=(4, 12)),
    cards(seed=88, n=12, base=1417, amp=98, period=8200,
          w_a=400, w_b=537, op_a=.80, op_b=1.0, rot=9, z=9, phase=0.35,
          jitter=44, accents=(1, 7, 10)),
]

print("tablet panels:", build(panels, bands, "tab2.html"))
