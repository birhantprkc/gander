#!/usr/bin/env python3
"""Where a mix's energy is, octave by octave. `python3 audio/octaves.py out/soundtrack.wav ...`

Per octave, not per FFT bin: a bin count flatters the wide high bands into looking like
content. The number that matters for a film most people will hear on a phone is how much
lives from 250 Hz to 4 kHz, because a phone speaker gives back almost nothing under 200.
"""
import sys
import numpy as np
from scipy.io import wavfile

EDGES = [22, 45, 90, 180, 355, 710, 1400, 2800, 5600, 11200, 20000]
NAMES = ["31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]

print("            " + "".join(f"{n:>6}" for n in NAMES) + "   250-4k  <125")
for path in sys.argv[1:]:
    sr, x = wavfile.read(path)
    x = x.astype(np.float64).mean(1) if x.ndim > 1 else x.astype(np.float64)
    spec = np.abs(np.fft.rfft(x)) ** 2
    f = np.fft.rfftfreq(len(x), 1 / sr)
    e = np.array([spec[(f >= a) & (f < b)].sum() for a, b in zip(EDGES, EDGES[1:])])
    pct = 100 * e / e.sum()
    name = path.split("/")[-1].replace("soundtrack", "mix").replace(".wav", "")[:11]
    print(f"{name:<12}" + "".join(f"{p:6.1f}" for p in pct) + f"   {pct[3:8].sum():5.1f}%  {pct[:2].sum():4.1f}%")
