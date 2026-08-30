#!/usr/bin/env python3
"""Record docs/demo.gif: recents, folder browsing, PDF, Word, Excel, Markdown.

Run it with the emulator up, the samples pushed, a folder already granted and
recents populated, i.e. the same device state the still captures want:

    python3 scripts/screenshots/record_demo.py

Writes docs/demo.gif and leaves the intermediates in build/ next to it.

Three things about this are not obvious, and each one cost a wasted recording.

**screenrecord only emits a frame when the screen changes.** A 2.2s hold on a
document produces three near-identical frames, not sixty. Encoding the result at
a constant frame rate therefore destroys the pacing: the holds collapse to a
flash and the whole thing races. The dwell time lives in the presentation
timestamps, so this reads them back with ffprobe and gives every frame its own
delay. It also means the capture is bursty - about 40fps through a transition and
nothing at all during a hold - and that roughly 70% of the frames it does write
are exact duplicates.

**"Has the new screen rendered yet" cannot be answered by "is the content
non-blank".** The screen being navigated away from is itself non-blank, so that
test passes instantly and every tap lands on the wrong screen; the first attempt
produced a recording in which all four viewers were blank. Readiness here means
the content area has *changed from the screen that was tapped on* and then
stopped moving. Sample the reference fingerprint before the tap, never after.

**The interesting frames are a small minority.** A raw recording of this flow is
about 60% one scrolling PDF, plus three seconds of loading screens - and the
Excel spinner alone outlasts Excel. So the GIF is not assembled from the whole
recording: frames are grouped by their toolbar strip, loading screens are
dropped, the scroll is thinned, and each document gets a deliberate dwell. That
is also what keeps the file small; the first balanced cut was 540kB against
1.4MB for the same footage played straight.
"""
import glob
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
OUT = REPO / "docs" / "demo.gif"
BUILD = REPO / "docs" / "screenshots" / "demo-build"

WIDTH = 300              # the README renders it at width=300
CONTENT = "1080x1200+0+400"
CHANGED = 12.0           # RMSE at which the screen has really turned over
SETTLED = 2.0            # ...and below which it has stopped moving
DARK = 150               # a painted page is ~230; the "Rendering document" card ~87

DOCS_ROW = (540, 1235)   # the Documents folder on the home screen
ROWS = [("Tenancy Agreement", "PDF"), ("Field Survey Report", "Word"),
        ("Q3 Operating Budget", "Excel"), ("Willowmere site notes", "Markdown")]

# Deliberate pacing, in seconds. Straight playback gives the PDF six times the
# screen time of everything else, which reads as "a PDF scrolling" rather than
# "opens everything".
DWELL = {"home": 0.60, "folder_step": 0.16, "folder": 0.70, "folder_brief": 0.35,
         "doc_in": 0.65, "scroll": 0.13, "doc_out": 0.55, "doc": 1.50, "end": 0.90}


def _mag(args, data=None):
    # No text=True when piping a PNG in: Popen would try to .encode() it.
    return subprocess.run(["magick", *args], input=data, capture_output=True).stdout


def fingerprint():
    png = subprocess.run([ui.ADB, "exec-out", "screencap", "-p"],
                         capture_output=True).stdout
    return _mag(["png:-", "-crop", CONTENT, "+repage", "-colorspace", "Gray",
                 "-resize", "32x32!", "-depth", "8", "gray:-"], png)


def rmse(a, b):
    if not a or not b or len(a) != len(b):
        return 255.0
    return (sum((x - y) ** 2 for x, y in zip(a, b)) / len(a)) ** 0.5


def wait_rendered(label, before, timeout=25.0):
    """Wait for the content to differ from `before`, then settle."""
    end, prev = time.time() + timeout, None
    while time.time() < end:
        cur = fingerprint()
        if rmse(cur, before) > CHANGED and prev is not None and rmse(cur, prev) < SETTLED:
            return cur
        prev = cur
        time.sleep(0.45)
    print(f"    {label}: TIMED OUT waiting to settle")
    return fingerprint()


def record():
    ui.shell("cmd uimode night no")
    ui.demo_mode()
    ui.launch()
    time.sleep(2.0)
    if ui.by_text("Recent files") is None:
        raise SystemExit("not on the home screen: populate recents first")

    BUILD.mkdir(parents=True, exist_ok=True)
    rec = subprocess.Popen([ui.ADB, "shell", "screenrecord", "--bit-rate", "8000000",
                            "--time-limit", "180", "/sdcard/demo.mp4"])
    time.sleep(2.5)                       # let the encoder settle

    time.sleep(DWELL["home"] * 3)
    fp = fingerprint()
    ui.tap(*DOCS_ROW, wait=0)
    fp = wait_rendered("folder", fp, timeout=15)
    time.sleep(1.8)

    for probe, label in ROWS:
        if not ui.tap_text(probe, wait=0.4, min_y=250, cls="TextView", tries=3):
            print(f"    {label}: row not found ({probe!r})")
            continue
        wait_rendered(label, fp)
        time.sleep(2.2)
        if label == "PDF":
            ui.swipe(540, 1450, 540, 700, ms=420, wait=0)
            time.sleep(1.4)
        viewer = fingerprint()            # sample before leaving, not after
        ui.back(wait=0)
        fp = wait_rendered("folder", viewer, timeout=15)
        time.sleep(0.7)

    ui.back(wait=0)
    wait_rendered("home", fp, timeout=15)
    time.sleep(1.5)

    ui.shell("pkill -INT screenrecord")
    rec.wait(timeout=40)
    time.sleep(2.0)
    subprocess.run([ui.ADB, "pull", "/sdcard/demo.mp4", str(BUILD / "demo.mp4")],
                   capture_output=True)
    ui.demo_exit()
    return BUILD / "demo.mp4"


def assemble(mp4):
    from PIL import Image, ImageChops, ImageStat

    frames_dir = BUILD / "frames"
    shutil.rmtree(frames_dir, ignore_errors=True)
    frames_dir.mkdir()
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(mp4), "-vsync", "0",
                    "-vf", f"scale={WIDTH}:-2", str(frames_dir / "f_%04d.png")],
                   capture_output=True)
    frames = sorted(glob.glob(str(frames_dir / "f_*.png")))

    def crop(p, box):
        return Image.open(p).crop(box).convert("L")

    def d(a, b):
        return ImageStat.Stat(ImageChops.difference(a, b)).rms[0]

    h = Image.open(frames[0]).height

    # Collapse duplicates FIRST. About 70% of what screenrecord writes is an
    # exact repeat, and without this a static folder listing held for two
    # seconds looks like a 27-frame run and gets treated as a scroll.
    deduped, last = [], None
    for p in frames:
        s = crop(p, (0, 0, WIDTH, h)).resize((64, 64))
        if last is not None and d(s, last) < 0.3:
            continue
        deduped.append(p)
        last = s
    frames = deduped

    # Group by the toolbar strip: it names the screen, and is stable while the
    # body scrolls.
    bars = [crop(p, (0, 20, WIDTH, 64)).resize((48, 8)) for p in frames]
    groups, refs = [], []
    for b in bars:
        for i, r in enumerate(refs):
            if d(b, r) < 6:
                groups.append(i)
                break
        else:
            refs.append(b)
            groups.append(len(refs) - 1)

    def painted(p):
        """False for a loading screen: blank, or carrying the 'Rendering
        document' card.

        Mean brightness does not separate these. The card's dark band covers
        only about a third of the body, so the white around it lifts the mean to
        230 against a page's 239, and its dark-pixel fraction (0.12) sits too
        close to a dense listing's (0.06) to threshold safely. What no text page
        ever has is a *full-width* dark row: glyphs are thin and leave the row
        mostly white. Measured, the card scores 44 such rows and every real
        screen scores 0 to 3.
        """
        im = crop(p, (0, 70, WIDTH, int(h * 0.88)))
        w, hh = im.size
        data = im.tobytes()                      # mode "L", so one byte per pixel
        cols = range(0, w, 4)
        dark_rows = sum(
            1 for y in range(hh)
            if sum(data[y * w + x] for x in cols) / len(cols) < DARK
        )
        return dark_rows <= 20 and ImageStat.Stat(im).stddev[0] > 8

    lit = [painted(p) for p in frames]

    runs = []
    for i, g in enumerate(groups):
        if runs and runs[-1][0] == g and runs[-1][2] == lit[i]:
            runs[-1][1].append(i)
        else:
            runs.append([g, [i], lit[i]])
    runs = [r for r in runs if r[2]]                             # drop loading screens

    seq = []
    for n, (_, idx, _) in enumerate(runs):
        first, last = idx[0], idx[-1]
        if len(idx) > 12:                                        # the scrolling document
            thinned = [first] + idx[3::4] + [last]
            seq.append((first, DWELL["doc_in"]))
            seq += [(i, DWELL["scroll"]) for i in thinned[1:-1]]
            seq.append((last, DWELL["doc_out"]))
        elif n in (0, len(runs) - 1):                            # opening / closing home
            seq += [(first, DWELL["home"]), (last, DWELL["end" if n else "home"])]
        elif len(idx) >= 4:                                      # the folder listing
            seq += [(i, DWELL["folder_step"]) for i in idx[:4]] + [(last, DWELL["folder"])]
        elif len(idx) == 1:                                      # a document, one frame
            seq.append((first, DWELL["doc"]))
        else:
            seq.append((last, DWELL["folder_brief"]))

    listing = BUILD / "frames.txt"
    with open(listing, "w") as fh:
        for i, sec in seq:
            fh.write(f"file '{frames[i]}'\nduration {sec:.3f}\n")
        fh.write(f"file '{frames[seq[-1][0]]}'\n")

    # bayer, not none: an ordered pattern is stable frame to frame, so unchanged
    # regions stay byte-identical and diff_mode=rectangle can skip them. Error
    # diffusion and no-dither both cost several times the size here.
    pal = BUILD / "palette.png"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-vf", "palettegen=max_colors=48:stats_mode=diff", "-y", str(pal)],
                   capture_output=True)
    subprocess.run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-i", str(pal), "-lavfi",
                    "paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
                    "-loop", "0", "-y", str(OUT)], capture_output=True)

    delays = [int(x) for x in _mag(["identify", "-format", "%T ", str(OUT)]).split()]
    print(f"{len(frames)} recorded -> {len(seq)} kept; {len(delays)} frames, "
          f"{sum(delays)/100:.1f}s, {OUT.stat().st_size/1024:.0f}kB -> {OUT}")
    json.dump([[i, round(s, 3)] for i, s in seq], open(BUILD / "sequence.json", "w"))


if __name__ == "__main__":
    assemble(record())
