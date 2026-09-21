#!/usr/bin/env python3
"""The narration, one WAV per line, from Kokoro (Apache-2.0 weights, run locally).

    .venv/bin/python audio/voice.py                 # the default voice
    .venv/bin/python audio/voice.py --voice bm_george
    .venv/bin/python audio/voice.py --check         # and transcribe each line back, if
                                                    # faster-whisper is installed

Not the Mac's own `say` voices: Apple licenses those for personal, non-commercial use, which
a film about an app is not. Kokoro's weights are Apache-2.0 and nothing leaves the machine,
which is the only kind of voice a film about Gander could decently use.

Each line is pinned to a cue from out/cues.json, never to a number typed here, so a line
moves with the picture it belongs to. `after` is how long after the cue it starts. `say` is
what the synthesiser is given when that differs from what a person would write.
"""
import argparse, json, pathlib, re, sys
import numpy as np
import soundfile as sf

HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / "out" / "voice"

# (id, cue name, which occurrence of it, seconds after, the line, how to say it)
LINES = [
    ("sent",     "headline", 0, 0.10, "Someone sent you a file.", None),
    ("wanted",   "dialog",   2, 0.15, "All you wanted was to open it.", None),
    ("gander",   "title",    0, 0.10, "Take a gander.", None),
    ("opens",    "file",     0, 0.10, "Gander opens everything.", None),
    ("pdf",      "file",     1, 0.12, "PDFs.", "P D Fs."),
    ("word",     "file",     2, 0.12, "Word.", None),
    ("slides",   "file",     3, 0.12, "Slides.", None),
    ("photos",   "file",     4, 0.12, "Photos.", None),
    ("video",    "file",     5, 0.12, "Video.", None),
    ("audio",    "file",     6, 0.12, "Audio.", None),
    ("markdown", "file",     7, 0.12, "Markdown.", None),
    ("zip",      "file",     8, 0.12, "Zip files.", None),
    ("else",     "file",     9, 0.15, "And practically anything else.", None),
    ("finds",    "find",     0, 0.30, "It finds anything.", None),
    ("pixel",    "photo",    0, 0.35, "It zooms in on every last pixel.", None),
    ("night",    "night",    0, 0.75, "It reads at 2am.", "It reads at two A M."),
    ("nothing",  "nothing",  0, 0.30, "And it takes nothing.", None),
    ("perms",    "strike",   1, 0.10, "No permissions.", None),
    ("net",      "noNet",    0, 0.18, "No internet access.", None),
    ("promise",  "cut",      0, 0.12, "Not a promise. A missing capability.", None),
    ("rest",     "check",    0, 0.36, "No ads, no trackers, no accounts.", None),
    ("tiny",     "fact",     0, 0.05, "Tiny.", None),
    ("free",     "fact",     1, 0.05, "Free.", None),
    ("open",     "fact",     2, 0.05, "Open source.", None),
    ("name",     "wordmark", 0, 0.10, "Gander.", None),
    ("tagline",  "tagline",  0, 0.30, "Take a gander at any file.", None),
]


# The one line with less room than it needs at an easy pace.
SPEED = {"promise": 1.08}


def trim(y, sr, floor=0.004):
    """Kokoro pads its output; the mix wants each line to start on its first sound."""
    env = np.abs(y)
    idx = np.where(env > floor)[0]
    if not len(idx):
        return y
    a, b = max(0, idx[0] - int(0.012 * sr)), min(len(y), idx[-1] + int(0.06 * sr))
    return y[a:b]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voice", default="af_heart")
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    import tempfile
    import espeakng_loader
    from kokoro_onnx import Kokoro
    from kokoro_onnx.config import EspeakConfig
    cues = json.loads((HERE / "out" / "cues.json").read_text())["cues"]
    # espeak-ng copies its data path into a 160-byte buffer and, when it does not fit, says
    # nothing and goes looking where it was compiled, which is a directory on somebody's build
    # server. A venv inside a worktree is well past 160. A symlink from somewhere short does
    # not help, because phonemizer resolves the path before handing it over, so the 19 MB of
    # data is copied, once, to a real directory with a short name.
    short = pathlib.Path(tempfile.gettempdir()) / "gander-espeak-data"
    if short.is_symlink():
        short.unlink()
    if not (short / "phontab").exists():
        import shutil
        shutil.copytree(espeakng_loader.get_data_path(), short, dirs_exist_ok=True)
    espeak = EspeakConfig(lib_path=espeakng_loader.get_library_path(), data_path=str(short))
    kokoro = Kokoro(str(HERE / "models" / "kokoro-v1.0.onnx"), str(HERE / "models" / "voices-v1.0.bin"), espeak_config=espeak)
    OUT.mkdir(parents=True, exist_ok=True)

    manifest = []
    for lid, cue, nth, after, text, say in LINES:
        hits = [c for c in cues if c["name"] == cue]
        if nth >= len(hits):
            sys.exit(f"{lid}: the film has no cue {cue}[{nth}]")
        y, sr = kokoro.create(say or text, voice=args.voice, speed=args.speed * SPEED.get(lid, 1.0), lang="en-us")
        y = trim(np.asarray(y, dtype=np.float64), sr)
        sf.write(OUT / f"{lid}.wav", y, sr, subtype="PCM_24")
        manifest.append({"id": lid, "text": text, "at": round(hits[nth]["t"] + after, 3), "dur": round(len(y) / sr, 3)})

    # A line must be over before the next one starts, or the film talks over itself.
    clashes = []
    for a, b in zip(manifest, manifest[1:]):
        gap = b["at"] - (a["at"] + a["dur"])
        if gap < 0.08:
            clashes.append(f'  "{a["text"]}" runs {-gap + 0.08:.2f}s into "{b["text"]}"')
    (OUT / "lines.json").write_text(json.dumps({"voice": args.voice, "lines": manifest}, indent=1) + "\n")
    print(f"{len(manifest)} lines in {args.voice}, {sum(m['dur'] for m in manifest):.1f}s of speech")
    if clashes:
        print("clashes:\n" + "\n".join(clashes))

    if args.check:
        from faster_whisper import WhisperModel
        model = WhisperModel("base.en", device="cpu", compute_type="int8")
        norm = lambda s: re.sub(r"[^a-z0-9 ]", "", s.lower().replace("2am", "2 am").replace("two am", "2 am")).split()
        wrong = 0
        for m in manifest:
            segs, _ = model.transcribe(str(OUT / f"{m['id']}.wav"), beam_size=5, language="en")
            heard = " ".join(s.text.strip() for s in segs)
            ok = norm(heard) == norm(m["text"])
            wrong += not ok
            print(("  ok   " if ok else "  DIFF ") + f'{m["text"]!r:42} heard {heard!r}')
        print(f"{len(manifest) - wrong} of {len(manifest)} lines come back word for word")
    sys.exit(1 if clashes else 0)


if __name__ == "__main__":
    main()
