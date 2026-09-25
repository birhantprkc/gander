#!/usr/bin/env python3
"""Renders the 512 px store icon from the launcher icon's drawable.

Why this exists
---------------
fastlane/metadata/android/en-US/images/icon.png is the icon Play and F-Droid show, and the press
kit carries a copy of it as docs/press/assets/gander-icon-512.png. Both were made in July 2026
with nothing in the repo to make them again, so a change to the launcher icon never reached
them on its own.

This draws them from ic_launcher_foreground.xml: the whole 108-unit canvas at 512 px, over the
gradient those PNGs have always had, with their corners rounded at 78 px. The gradient and the
corners were measured from the July PNG. Drawn this way, the July foreground lands within a fifth
of a pixel of it, and the background within one level per channel.

Usage
-----
    python3 scripts/make-store-icon.py

Needs Chrome, Chromium or Brave, like render-social-preview.sh. Rebuild the press kit ZIP with
scripts/make-press-kit.sh afterwards.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FOREGROUND = os.path.join(REPO, "app", "src", "main", "res", "drawable", "ic_launcher_foreground.xml")
OUTPUTS = [
    os.path.join(REPO, "fastlane", "metadata", "android", "en-US", "images", "icon.png"),
    os.path.join(REPO, "docs", "press", "assets", "gander-icon-512.png"),
]

ANDROID = "{http://schemas.android.com/apk/res/android}"
SIZE = 512
CORNER = 78
# Lighter at the top left, warmer at the bottom right: #F8F4EA at the corner, #F1E9D7 where the
# gradient line meets the far corner's projection. Fitted from the July PNG to within one level.
GROUND = ("#F8F4EA", "#F1E9D7", (0, 0), (222.3, 615.3))

CHROMES = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
    shutil.which("google-chrome") or "",
    shutil.which("chromium") or "",
]


def colour(argb):
    """VectorDrawable's #AARRGGBB (or #RRGGBB) as an SVG colour and an opacity."""
    value = argb.lstrip("#")
    if len(value) == 8:
        return "#" + value[2:], int(value[:2], 16) / 255
    return "#" + value, 1.0


def svg(element):
    """The drawable's groups and paths as SVG, transforms composed the way VectorDrawable does."""
    out = []
    for child in element:
        attr = lambda name, default="0": child.get(ANDROID + name, default)
        if child.tag == "group":
            px, py = attr("pivotX"), attr("pivotY")
            transform = (f"translate({float(attr('translateX')) + float(px)},"
                         f"{float(attr('translateY')) + float(py)}) "
                         f"rotate({attr('rotation')}) "
                         f"scale({attr('scaleX', '1')},{attr('scaleY', '1')}) "
                         f"translate({-float(px)},{-float(py)})")
            out.append(f'<g transform="{transform}">{svg(child)}</g>')
        elif child.tag == "path":
            parts = [f'd="{attr("pathData")}"']
            if child.get(ANDROID + "fillColor"):
                fill, opacity = colour(attr("fillColor"))
                parts.append(f'fill="{fill}" fill-opacity="{opacity:.3f}"')
            else:
                parts.append('fill="none"')
            if child.get(ANDROID + "fillType") == "evenOdd":
                parts.append('fill-rule="evenodd"')
            if child.get(ANDROID + "strokeColor"):
                stroke, opacity = colour(attr("strokeColor"))
                parts.append(f'stroke="{stroke}" stroke-opacity="{opacity:.3f}" '
                             f'stroke-width="{attr("strokeWidth")}" '
                             f'stroke-linejoin="{attr("strokeLineJoin", "miter")}"')
            out.append(f"<path {' '.join(parts)}/>")
    return "".join(out)


def page(foreground):
    start, end, (x1, y1), (x2, y2) = GROUND
    root = ET.fromstring(foreground)
    scale = SIZE / float(root.attrib[ANDROID + "viewportWidth"])
    return f'''<!doctype html><html><body style="margin:0;background:transparent">
<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" viewBox="0 0 {SIZE} {SIZE}">
  <defs>
    <linearGradient id="ground" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">
      <stop offset="0" stop-color="{start}"/><stop offset="1" stop-color="{end}"/>
    </linearGradient>
    <clipPath id="corners"><rect width="{SIZE}" height="{SIZE}" rx="{CORNER}"/></clipPath>
  </defs>
  <g clip-path="url(#corners)">
    <rect width="{SIZE}" height="{SIZE}" fill="url(#ground)"/>
    <g transform="scale({scale})">{svg(root)}</g>
  </g>
</svg></body></html>'''


def render(foreground, out):
    chrome = next((c for c in CHROMES if c and os.access(c, os.X_OK)), None)
    if chrome is None:
        sys.exit("No Chrome, Chromium or Brave found.")
    with tempfile.TemporaryDirectory() as tmp:
        html = os.path.join(tmp, "icon.html")
        with open(html, "w") as f:
            f.write(page(foreground))
        subprocess.run([chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=1", "--default-background-color=00000000",
                        f"--window-size={SIZE},{SIZE}", f"--screenshot={out}", "file://" + html],
                       check=True, capture_output=True)


def main():
    with open(FOREGROUND) as f:
        foreground = f.read()
    render(foreground, OUTPUTS[0])
    for copy in OUTPUTS[1:]:
        shutil.copyfile(OUTPUTS[0], copy)
    for path in OUTPUTS:
        print(f"Wrote {os.path.relpath(path, REPO)}")


if __name__ == "__main__":
    main()
