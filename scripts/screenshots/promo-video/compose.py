#!/usr/bin/env python3
"""Assemble the promo: trim the raw clips, chain them with crossfades into one phone-screen
stream, then composite ground / screen / frame / captions / wordmark and mix the bed.

    python3 compose.py sheet          # contact sheets of every raw clip, for choosing cuts
    python3 compose.py build          # segments -> screen.mp4 -> out/gander-promo.mp4
"""
import os, sys, json, subprocess
HERE = os.path.dirname(os.path.abspath(__file__))
RAW, SEG, OUT, LAY = (os.path.join(HERE, d) for d in ("raw", "seg", "out", "layers"))
FPS = 60
GROUND = "0x17130C"
SCREEN = (1150, 96, 500, 889)

# name, source clip, in, out (seconds in the raw clip), hold (freeze the last frame this long),
# fade into the *next* segment (seconds), optional extra -vf filters on the 1080x1920 source
PATCH = "drawbox=x=150:y=476:w=360:h=54:color=0xE9E7DD:t=fill"   # About's "Version x.y" line
SEGS = [
    # name        source              in     out    hold   xfade  extra
    ("pdf",      "open-pdf.webm",    1.00,  6.00,  0.00,  0.12, None),
    ("docx",     "open-docx.webm",   1.40,  3.25,  0.00,  0.12, None),
    ("xlsx",     "open-xlsx.webm",   1.40,  3.75,  0.00,  0.12, None),
    ("pptx",     "open-pptx.webm",   1.40,  3.50,  0.00,  0.12, None),
    ("jpg",      "open-jpg.webm",    1.40,  3.40,  0.00,  0.35, None),
    ("about",    "about.webm",       2.60,  5.60,  0.00,  0.35, PATCH),
    ("appinfo",  "appinfo.webm",     2.90,  5.90,  0.00,  0.35, None),
    ("finda",    "find.mp4",         1.10,  4.00,  0.00,  0.12, None),   # search, type, 1/5
    ("findb",    "find.mp4",         5.90, 10.60,  0.00,  0.35, None),   # keyboard down, next x3
    ("sheet",    "sheet.mp4",        0.60,  6.10,  0.00,  0.35, None),
    ("deck",     "deck.mp4",         0.50,  4.50,  0.00,  0.35, None),
    ("folder",   "folder.webm",      0.75,  5.60,  0.00,  0.35, None),
    ("mdlight",  "md-light.webm",    2.20,  3.60,  0.00,  0.80, None),
    ("dark",     "dark.webm",        2.40,  5.00,  4.50,  0.00, None),
]
# caption, segment its start is measured from, offset, segment its end is measured from, offset
# (negative: from that segment's end)
BEATS = [
    ("open",    "pdf",     0.00, "pdf",     1.95),
    ("opens",   "pdf",     2.10, "jpg",    -0.10),
    ("nothing", "about",   0.05, "appinfo", -0.10),
    ("frag-perms", "appinfo", 0.55, "appinfo", -0.15),   # the OS's own words, lifted out and magnified
    ("finds",   "finda",   0.05, "findb",  -0.10),
    ("sheet",   "sheet",   0.05, "sheet",  -0.10),
    ("deck",    "deck",    0.05, "deck",   -0.10),
    ("folders", "folder",  0.05, "folder", -0.10),
    ("2am",     "mdlight", 0.30, "dark",    3.20),
    ("end",     "dark",    3.40, "dark",   99.0),
]
XF_DEFAULT = 0.35


def run(cmd):
    print(" ".join(cmd) if len(" ".join(cmd)) < 400 else cmd[0] + " ... " + cmd[-1], flush=True)
    subprocess.run(cmd, check=True)


def sheet():
    os.makedirs(os.path.join(OUT, "sheets"), exist_ok=True)
    for f in sorted(os.listdir(RAW)):
        if not f.endswith(".mp4"): continue
        run(["ffmpeg", "-y", "-v", "error", "-i", os.path.join(RAW, f),
             "-vf", "fps=4,scale=150:-1,drawtext=text='%{pts\\:hms}':fontsize=18:fontcolor=yellow:"
                    "box=1:boxcolor=black@0.6:x=4:y=4,tile=10x4:padding=2:color=0x333333",
             "-frames:v", "1", os.path.join(OUT, "sheets", f.replace(".mp4", ".png"))])


def seg_duration(s):
    return (s[3] - s[2]) + s[4]


def build():
    os.makedirs(SEG, exist_ok=True)
    # 1. segments, exact length, constant 60 fps
    for (name, src, tin, tout, hold, xf, extra) in SEGS:
        dur = seg_duration((name, src, tin, tout, hold, xf, extra))
        vf = (f"trim=start={tin}:end={tout},setpts=PTS-STARTPTS,fps={FPS},"
              f"tpad=stop_mode=clone:stop_duration={hold + 2.0},trim=duration={dur:.4f},setpts=PTS-STARTPTS")
        if extra: vf += "," + extra
        cmd = ["ffmpeg", "-y", "-v", "error", "-i", os.path.join(RAW, src)]
        if src.endswith(".mp4"):
            # adb shell screenrecord omits the display cutout that the emulator capture draws;
            # paint the same hole-punch on so it does not blink between cuts
            cmd += ["-i", os.path.join(LAY, "hole.png"), "-filter_complex", f"[0:v]{vf}[t];[t][1:v]overlay=0:0:format=auto[v]", "-map", "[v]"]
        else:
            cmd += ["-vf", vf]
        run(cmd + ["-r", str(FPS), "-c:v", "libx264", "-crf", "14", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-an",
                   os.path.join(SEG, name + ".mp4")])
    # 2. crossfade chain -> screen.mp4; remember where each segment starts on the final timeline
    starts, t = {}, 0.0
    for i, s in enumerate(SEGS):
        starts[s[0]] = t
        t += seg_duration(s) - (s[5] if i < len(SEGS) - 1 else 0)
    total = t
    inputs, fc, prev = [], [], "[0:v]"
    for i, s in enumerate(SEGS):
        inputs += ["-i", os.path.join(SEG, s[0] + ".mp4")]
    off = 0.0
    for i in range(1, len(SEGS)):
        prev_seg = SEGS[i - 1]
        off += seg_duration(prev_seg) - prev_seg[5]
        out = f"[x{i}]" if i < len(SEGS) - 1 else "[screen]"
        fc.append(f"{prev}[{i}:v]xfade=transition=fade:duration={prev_seg[5]}:offset={off:.4f}{out}")
        prev = out
    run(["ffmpeg", "-y", "-v", "error"] + inputs + ["-filter_complex", ";".join(fc), "-map", "[screen]",
         "-r", str(FPS), "-c:v", "libx264", "-crf", "14", "-preset", "veryfast", "-pix_fmt", "yuv420p",
         os.path.join(SEG, "screen.mp4")])
    json.dump({"total": total, "starts": starts}, open(os.path.join(SEG, "timeline.json"), "w"))
    bed(total)
    composite()


def timeline():
    """Where each segment starts on the final timeline, and the total, from SEGS alone."""
    starts, t = {}, 0.0
    for i, s in enumerate(SEGS):
        starts[s[0]] = t
        t += seg_duration(s) - (s[5] if i < len(SEGS) - 1 else 0)
    return starts, t


def bed(total):
    """The bed, exactly as long as the picture (or a supplied track, faded out at the end)."""
    out = os.path.join(OUT, "bed.wav")
    if os.environ.get("GANDER_BED"):
        run(["ffmpeg", "-y", "-v", "error", "-i", os.environ["GANDER_BED"], "-t", f"{total:.3f}",
             "-af", f"afade=t=out:st={total - 3:.3f}:d=3", "-ar", "48000", out])
    else:
        run(["python3", os.path.join(HERE, "music.py"), out, f"{total:.2f}"])


def composite():
    """Ground / screen / frame / wordmark / captions / bed -> out/gander-promo.mp4.

    Static layers are single-frame inputs that overlay repeats (a looped PNG decodes the image
    60 times a second, which made the first cut of this run at 0.5 fps). Captions are cropped to
    their ink and exist only for their own window, shifted into place with setpts."""
    from PIL import Image
    starts, total = timeline()
    sx, sy, sw, sh = SCREEN
    caps = []
    for (cap, s_seg, s_off, e_seg, e_off) in BEATS:
        st = starts[s_seg] + s_off
        e_len = seg_duration(next(s for s in SEGS if s[0] == e_seg))
        en = min(total, starts[e_seg] + (e_off if e_off >= 0 else e_len + e_off))
        img = Image.open(os.path.join(LAY, f"cap-{cap}.png"))
        bx = img.getbbox()
        crop = os.path.join(SEG, f"cap-{cap}.png")
        img.crop((bx[0] - 4, bx[1] - 4, bx[2] + 4, bx[3] + 30)).save(crop)   # room for the 22px rise
        caps.append((cap, round(st, 3), round(en, 3), crop, bx[0] - 4, bx[1] - 4))
    ins = ["-f", "lavfi", "-i", f"color=c={GROUND}:s=1920x1080:r={FPS}:d={total:.3f}",
           "-i", os.path.join(LAY, "ground.png"),
           "-i", os.path.join(SEG, "screen.mp4"),
           "-i", os.path.join(LAY, "screenmask.png"),
           "-i", os.path.join(LAY, "frame.png"),
           "-i", os.path.join(LAY, "wordmark.png")]
    for (cap, st, en, crop, cx, cy) in caps:
        ins += ["-framerate", str(FPS), "-loop", "1", "-t", f"{en - st:.3f}", "-i", crop]
    ins += ["-i", os.path.join(OUT, "bed.wav")]
    fc = ["[0:v][1:v]overlay=0:0:format=auto[g]",
          f"[2:v]scale={sw}:{sh}:flags=lanczos,format=rgba[scr]", "[3:v]format=gray[msk]",
          "[scr][msk]alphamerge[scra]", f"[g][scra]overlay={sx}:{sy}:format=auto[a]",
          "[a][4:v]overlay=0:0:format=auto[b]", "[b][5:v]overlay=0:0:format=auto[base0]"]
    base = "[base0]"
    for k, (cap, st, en, crop, cx, cy) in enumerate(caps):
        idx = 6 + k
        fi, fo = 0.4, 0.3
        fc.append(f"[{idx}:v]format=rgba,setpts=PTS+{st:.3f}/TB,fade=t=in:st={st:.3f}:d={fi}:alpha=1,"
                  f"fade=t=out:st={en - fo:.3f}:d={fo}:alpha=1[c{k}]")
        fc.append(f"{base}[c{k}]overlay=x={cx}:y='{cy}+22*max(0,1-(t-{st:.3f})/{fi})':"
                  f"eof_action=pass:eval=frame:format=auto[base{k + 1}]")
        base = f"[base{k + 1}]"
    fc.append(f"{base}format=yuv420p,fade=t=in:st=0:d=0.7:color={GROUND},"
              f"fade=t=out:st={total - 0.9:.3f}:d=0.9:color={GROUND}[v]")
    aidx = 6 + len(caps)
    run(["ffmpeg", "-y", "-v", "error", "-stats"] + ins + ["-filter_complex", ";".join(fc),
         "-map", "[v]", "-map", f"{aidx}:a", "-t", f"{total:.3f}",
         "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p", "-r", str(FPS),
         "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
         "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", os.path.join(OUT, "gander-promo.mp4")])
    json.dump({"total": total, "starts": starts, "captions": [c[:3] for c in caps]},
              open(os.path.join(OUT, "timeline.json"), "w"), indent=1)
    print(f"total {total:.2f}s"); print(json.dumps([c[:3] for c in caps], indent=1))


def thumbnail():
    """1280x720 YouTube thumbnail: the end card, from the finished video's last held second."""
    tl = json.load(open(os.path.join(OUT, "timeline.json")))
    run(["ffmpeg", "-y", "-v", "error", "-ss", f"{tl['total'] - 1.6:.3f}", "-i", os.path.join(OUT, "gander-promo.mp4"),
         "-frames:v", "1", "-vf", "scale=1280:720:flags=lanczos", "-q:v", "2", os.path.join(OUT, "thumbnail.jpg")])


if __name__ == "__main__":
    {"sheet": sheet, "build": build, "composite": composite, "thumbnail": thumbnail}[sys.argv[1]]()
