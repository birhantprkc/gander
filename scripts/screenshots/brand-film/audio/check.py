#!/usr/bin/env python3
"""What can be checked about a soundtrack without listening to it.

    .venv/bin/python audio/check.py

Two questions. Can the narration still be understood with everything else playing, which is
asked of a speech recogniser given the finished mix rather than the clean voice. And is
there really a sound where the film says something happens, which is asked of the effects
stem: at each cue that ought to be a transient, either the 30 ms after it is louder than the
30 ms before, or, where sounds come too thick for that to mean anything (sixteen chips in
under a second), there is at least sound there. A cue whose name the score never learned
shows up here as silence.

Neither says it sounds good. That takes a person.
"""
import difflib, json, pathlib, re, sys
import numpy as np
from scipy.io import wavfile

HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / "out"
SHARP = ["land", "tap", "dialog", "honk", "file", "chip", "key", "hit", "lens", "next", "dark", "bubble", "strike", "bump", "check", "tile", "fan", "lastHonk"]


def main():
    film = json.loads((OUT / "cues.json").read_text())
    lines = json.loads((OUT / "voice" / "lines.json").read_text())["lines"]
    failed = 0

    sr, sfx = wavfile.read(OUT / "stem-sfx.wav")
    _, mus = wavfile.read(OUT / "stem-music.wav")
    both, only = np.abs(sfx).max(1) + np.abs(mus).max(1), np.abs(sfx).max(1)
    n = lambda s: int(s * sr)
    silent = []
    for c in film["cues"]:
        if c["name"] not in SHARP:
            continue
        i = n(c["t"])
        after, before = both[i:i + n(0.03)], both[max(0, i - n(0.04)):max(1, i - n(0.01))]
        rises = np.sqrt(np.mean(after ** 2)) >= 1.25 * np.sqrt(np.mean(before ** 2)) + 1e-4
        sounding = np.sqrt(np.mean(only[i:i + n(0.05)] ** 2)) > 0.004
        if not (rises or sounding):
            silent.append(f'{c["name"]}@{c["t"]}')
    total = sum(c["name"] in SHARP for c in film["cues"])
    print(f"{total - len(silent)} of {total} sharp cues have a sound starting on them" + (": missing " + " ".join(silent) if silent else ""))
    failed += len(silent) > total * 0.1

    try:
        from faster_whisper import WhisperModel
    except ImportError:
        print("faster-whisper is not installed, so the narration was not checked in the mix")
        sys.exit(1 if failed else 0)
    model = WhisperModel("base.en", device="cpu", compute_type="int8")
    segs, _ = model.transcribe(str(OUT / "soundtrack.wav"), beam_size=5, language="en", vad_filter=False)
    norm = lambda s: re.sub(r"[^a-z0-9 ]", "", s.lower().replace("2am", "2 am").replace("2 a m", "2 am")).split()
    heard = norm(" ".join(s.text for s in segs))
    want = [w for ln in lines for w in norm(ln["text"])]
    # Words of the script found, in order, in what came back. Not greedily: one missed word
    # must not be allowed to find its match a minute later and skip everything between.
    match = difflib.SequenceMatcher(None, want, heard, autojunk=False)
    found = sum(m.size for m in match.get_matching_blocks())
    print(f"through the full mix, {found} of {len(want)} words of the script come back in order")
    missed = [w for tag, i, j, _, _ in match.get_opcodes() if tag != "equal" for w in want[i:j]]
    if missed:
        print("  not heard: " + " ".join(missed))
    failed += found < len(want) * 0.9
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
