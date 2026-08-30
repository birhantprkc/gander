"""A quiet, licence-clean music bed for the promo, synthesised from scratch.

Nothing sampled. Soft detuned pads on a slow four-chord loop, a sparse e-piano figure on
the chord tones, a felt-piano style low note under each change, and a long convolution
reverb. Deliberately restrained: the phone is the subject and this sits under it.
"""
import sys, math
import numpy as np
from scipy import signal
from scipy.io import wavfile

SR = 48000
DUR = float(sys.argv[2]) if len(sys.argv) > 2 else 46.0
OUT = sys.argv[1] if len(sys.argv) > 1 else "out/bed.wav"
rng = np.random.default_rng(3)

def note(n):  # MIDI -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def env(n, a, d, s, r, hold):
    """ADSR in samples; hold = total gate length in samples."""
    t = np.arange(n) / SR
    e = np.ones(n)
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    e[:a_n] = np.linspace(0, 1, a_n)
    if d_n: e[a_n:a_n + d_n] = np.linspace(1, s, d_n)
    e[a_n + d_n:hold] = s
    tail = n - hold
    if tail > 0: e[hold:] = s * np.exp(-np.arange(tail) / (r_n / 5 + 1))
    return e

def pad(freq, n, gain=1.0):
    t = np.arange(n) / SR
    out = np.zeros(n)
    for det, g in ((-0.006, .5), (0, 1), (0.005, .5), (0.011, .3)):
        f = freq * (1 + det)
        # soft saw: first 8 partials at 1/k, gently rolled off
        for k in range(1, 9):
            out += g * (1.0 / k) * (0.9 ** k) * np.sin(2 * math.pi * f * k * t + rng.uniform(0, 6.28))
    # slow amplitude shimmer
    out *= 1 + 0.06 * np.sin(2 * math.pi * 0.13 * t + rng.uniform(0, 6.28))
    return out * gain

def epiano(freq, n):
    t = np.arange(n) / SR
    tone = np.sin(2 * math.pi * freq * t) + 0.35 * np.sin(2 * math.pi * 2 * freq * t + 0.4) \
        + 0.12 * np.sin(2 * math.pi * 3 * freq * t)
    return tone * np.exp(-t * 1.6) * (1 - np.exp(-t * 400))

def lowpass(x, cutoff, order=2):
    b, a = signal.butter(order, cutoff / (SR / 2))
    return signal.lfilter(b, a, x)

def reverb(x, seconds=3.2, wet=0.35):
    n = int(seconds * SR)
    ir = rng.normal(0, 1, n) * np.exp(-np.arange(n) / (SR * seconds / 6))
    ir = lowpass(ir, 3200); ir /= np.sqrt((ir ** 2).sum())
    y = signal.fftconvolve(x, ir)[: len(x)]
    return (1 - wet) * x + wet * y / (np.abs(y).max() + 1e-9) * np.abs(x).max()

N = int(DUR * SR)
mix = np.zeros(N)
# Fmaj7 -> Am7 -> Dm9 -> Bbmaj7 (in F), 6 seconds a chord, pads overlapping by a second
chords = [(53, 57, 60, 64), (57, 60, 64, 67), (50, 53, 57, 60, 64), (46, 50, 53, 57)]
bar = 6.0
i = 0
t0 = 0.0
while t0 < DUR:
    ch = chords[i % len(chords)]
    n = int(min(bar + 1.5, DUR - t0) * SR)
    seg = np.zeros(n)
    for m in ch:
        seg += pad(note(m), n, gain=0.22 / len(ch))
    seg += pad(note(ch[0] - 12), n, gain=0.10)
    seg *= env(n, 1.4, 0.0, 1.0, 1.6, int(bar * SR))
    s0 = int(t0 * SR)
    mix[s0:s0 + n] += seg[: N - s0]
    # e-piano: two soft notes per bar on chord tones, above the pad
    for beat in (0.9, 3.6) if i % 2 == 0 else (1.8,):
        m = ch[rng.integers(1, len(ch))] + 12
        nn = int(4.0 * SR); s1 = int((t0 + beat) * SR)
        if s1 + 100 < N:
            e = epiano(note(m), nn) * 0.13
            mix[s1:s1 + nn] += e[: N - s1]
    i += 1; t0 += bar
mix = lowpass(mix, 2600, order=2)
mix = reverb(mix)
# fade in over 1.2 s, fade out over the last 3 s
fi, fo = int(1.2 * SR), int(3.0 * SR)
mix[:fi] *= np.linspace(0, 1, fi)
mix[-fo:] *= np.linspace(1, 0, fo) ** 1.5
mix /= np.abs(mix).max()
mix *= 10 ** (-9 / 20)          # peak -9 dBFS; a bed, not a track
stereo = np.stack([mix, np.roll(mix, 7)], axis=1)   # a touch of width
wavfile.write(OUT, SR, (stereo * 32767).astype(np.int16))
print("wrote", OUT, f"{DUR:.1f}s")
