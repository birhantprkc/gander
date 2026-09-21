#!/usr/bin/env python3
"""The film's soundtrack: score, sound design and narration, written to picture.

    python3 audio/score.py            # out/soundtrack.wav, out/soundtrack-no-voice.wav, stems
    python3 audio/score.py --report   # the same, and measure what came out

Nothing here knows a time. Every sound is hung on a cue from out/cues.json, which the film
writes from the same constants that move the picture (`node render.mjs --cues`), so the
soundtrack cannot drift from the thing it is describing: move a scene and its sounds move.

That is also the whole idea of it. A bed of music laid under a film sounds like a bed of
music. Here each dialog that pops up is the next note of a scale, the dialogs arrive as
quarters, then eighths, then sixteenths of the score's own bar, the format cuts fall on its
beats, the zip opens with a zip, 2am has crickets and sunrise has birds. 120 to the minute,
F major, down to D minor for the night and home again for the morning.

numpy and scipy only. The narration is made separately by audio/voice.py.
"""
import argparse, json, pathlib, re, subprocess, sys
import numpy as np
from scipy import signal
from scipy.io import wavfile
from scipy.ndimage import minimum_filter1d, uniform_filter1d

SR = 48000
HERE = pathlib.Path(__file__).resolve().parent.parent
OUT = HERE / "out"
rng = np.random.default_rng(7)  # seeded: the same film twice is the same soundtrack twice
N = lambda sec: max(1, int(round(sec * SR)))
T = lambda dur: np.arange(N(dur)) / SR
SEMI = {"C": 0, "C#": 1, "D": 2, "Eb": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "Ab": 8, "A": 9, "Bb": 10, "B": 11}
hz = lambda n: 440.0 * 2 ** ((SEMI[n[:-1]] + (int(n[-1]) - 4) * 12 - 9) / 12)
PENTA = ["D4", "F4", "G4", "A4", "C5", "D5", "F5", "G5", "A5", "C6", "D6", "F6", "G6", "A6", "C7", "D7"]


def butter(x, fc, kind, order=2):
    return signal.sosfilt(signal.butter(order, fc, kind, fs=SR, output="sos"), x, axis=0)


lp, hp = (lambda x, fc, o=2: butter(x, fc, "low", o)), (lambda x, fc, o=2: butter(x, fc, "high", o))
bp = lambda x, f0, f1, o=2: butter(x, [f0, min(f1, SR / 2 - 100)], "band", o)


def sweep(x, f0, f1, octaves=1.2, block=256):
    """Noise through a band that moves, which is most of what a whoosh is."""
    y, zi = np.zeros_like(x), np.zeros((1, 2))
    for i in range(0, len(x), block):
        fc = f0 * (f1 / f0) ** (i / max(1, len(x) - 1))
        sos = signal.butter(1, [fc / 2 ** (octaves / 2), min(fc * 2 ** (octaves / 2), SR / 2 - 200)], "band", fs=SR, output="sos")
        y[i:i + block], zi = signal.sosfilt(sos, x[i:i + block], zi=zi)
    return y


class Bus:
    def __init__(self, dur):
        self.a = np.zeros((N(dur), 2))

    def add(self, sig, t, gain=1.0, pan=0.0):
        if sig.ndim == 1:
            sig = np.stack([sig * np.cos((pan + 1) * np.pi / 4), sig * np.sin((pan + 1) * np.pi / 4)], 1)
        i = int(round(t * SR))
        if i < 0:
            sig, i = sig[-i:], 0
        sig = sig[: max(0, len(self.a) - i)]
        self.a[i:i + len(sig)] += sig * gain


# ---- instruments ------------------------------------------------------------------------------
def mallet(f, dur=1.3, bright=1.0):
    """A marimba bar: the fundamental, its double octave and a knock, each dying faster."""
    t, k = T(dur), (440 / f) ** 0.35
    y = np.sin(2 * np.pi * f * t) * np.exp(-t / (0.55 * k))
    y += 0.5 * bright * np.sin(2 * np.pi * 3.98 * f * t + 0.3) * np.exp(-t / (0.18 * k))
    y += 0.11 * bright * np.sin(2 * np.pi * 9.6 * f * t) * np.exp(-t / (0.05 * k))
    y += 0.34 * lp(rng.standard_normal(len(t)), 8000) * np.exp(-t / 0.004)
    return y * (1 - np.exp(-t / 0.0012))


def keys(f, dur=1.2):
    """Something between a felt piano and a Rhodes: soft, slightly out of tune with itself."""
    t, y = T(dur + 0.25), 0
    for k in range(1, 8):
        fk = k * f * np.sqrt(1 + 0.0004 * k * k)
        for det in (1.0, 1.0007):
            y = y + np.sin(2 * np.pi * fk * det * t + rng.uniform(0, 6.28)) / k ** 1.8 * np.exp(-t / (1.7 / k ** 0.9 * (261 / f) ** 0.3))
    env = (1 - np.exp(-t / 0.004)) * np.where(t < dur, 1.0, np.exp(-(t - dur) / 0.07))
    return lp(y * env, 3400) * 0.5


def pad(notes, dur, attack=0.7, release=1.1):
    t, out = T(dur + release), np.zeros((N(dur + release), 2))
    env = np.minimum(1, t / attack) * np.where(t < dur, 1.0, np.clip(1 - (t - dur) / release, 0, 1)) ** 2
    for n in notes:
        for cents, pan in ((-7, -0.7), (0, 0.0), (7, 0.7)):
            f = hz(n) * 2 ** (cents / 1200)
            v = sum(a * np.sin(2 * np.pi * f * h * t + rng.uniform(0, 6.28)) for h, a in ((1, 1), (2, 0.26), (3, 0.09)))
            v = v * env * (1 + 0.06 * np.sin(2 * np.pi * rng.uniform(0.15, 0.4) * t + rng.uniform(0, 6.28)))
            out += np.stack([v * np.cos((pan + 1) * np.pi / 4), v * np.sin((pan + 1) * np.pi / 4)], 1)
    return lp(out, 2200) / len(notes)


def bass(f, dur=0.5):
    t = T(dur + 0.1)
    y = np.sin(2 * np.pi * f * t) + 0.28 * np.sin(4 * np.pi * f * t) + 0.09 * np.sin(6 * np.pi * f * t)
    env = (1 - np.exp(-t / 0.006)) * (0.72 + 0.28 * np.exp(-t / 0.12)) * np.where(t < dur, 1.0, np.exp(-(t - dur) / 0.03))
    return np.tanh(1.4 * y * env) * 0.8


def bell(f, dur=1.6):
    t = T(dur)
    y = np.sin(2 * np.pi * f * t + 2.2 * np.exp(-t / 0.25) * np.sin(2 * np.pi * 3.5 * f * t)) * np.exp(-t / 0.55)
    return (y + 0.3 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t / 0.3)) * (1 - np.exp(-t / 0.002))


def lead(f, dur=0.14):
    """What the phone plays when it opens an audio file: bright, square-ish and a bit pleased."""
    t = T(dur + 0.08)
    y = sum(np.sin(2 * np.pi * f * h * t) / h for h in (1, 3, 5, 7, 9) if f * h < 9000)
    return lp(y * (1 - np.exp(-t / 0.003)) * np.exp(-t / 0.09), 5200)


def bloop(f, dur=0.2, drop=1.7, decay=0.06):
    """A pop with a pitch: starts sharp, lands on the note."""
    t = T(dur)
    ph = 2 * np.pi * np.cumsum(f * (1 + (drop - 1) * np.exp(-t / 0.018))) / SR
    return (np.sin(ph) + 0.12 * lp(rng.standard_normal(len(t)), 7000) * np.exp(-t / 0.002)) * np.exp(-t / decay) * (1 - np.exp(-t / 0.0008))


def kick():
    t = T(0.3)
    return np.sin(2 * np.pi * np.cumsum(46 + 70 * np.exp(-t / 0.028)) / SR) * np.exp(-t / 0.085) + 0.25 * lp(rng.standard_normal(len(t)), 3000) * np.exp(-t / 0.003)


def snap():
    t, y = T(0.22), 0
    for d in (0, 0.009, 0.019):
        y = y + np.where(t >= d, np.exp(-(t - d) / 0.035), 0) * rng.standard_normal(len(t))
    return bp(y, 1100, 3800) * 0.6


shaker = lambda v=1.0: hp(rng.standard_normal(N(0.07)), 6000) * np.exp(-T(0.07) / (0.012 + 0.014 * v)) * v
rim = lambda: (np.sin(2 * np.pi * 1750 * T(0.05)) + 0.5 * hp(rng.standard_normal(N(0.05)), 3000)) * np.exp(-T(0.05) / 0.008)
click = lambda fc=3500, d=0.004: bp(rng.standard_normal(N(0.03)), fc * 0.6, fc * 1.6) * np.exp(-T(0.03) / d)


def keyclick():
    t, c = T(0.06), click(4200, 0.003)
    return np.pad(c, (0, len(t) - len(c))) + 0.5 * np.sin(2 * np.pi * 190 * t) * np.exp(-t / 0.012)


def thud(f=130, dur=0.16):
    t = T(dur)
    return np.sin(2 * np.pi * np.cumsum(f * (0.62 + 0.38 * np.exp(-t / 0.03))) / SR) * np.exp(-t / 0.045) + 0.3 * lp(rng.standard_normal(len(t)), 900) * np.exp(-t / 0.012)


def whoosh(dur, f0, f1, pan0=0.0, pan1=0.0, skew=0.5, octaves=1.4):
    n = N(dur)
    x = np.linspace(0, 1, n)
    env = np.sin(np.pi * x ** (np.log(0.5) / np.log(skew))) ** 1.6
    y = sweep(rng.standard_normal(n), f0, f1, octaves) * env
    pan = np.linspace(pan0, pan1, n)
    return np.stack([y * np.cos((pan + 1) * np.pi / 4), y * np.sin((pan + 1) * np.pi / 4)], 1)


def flick():
    return hp(sweep(rng.standard_normal(N(0.09)), 1800, 6500, 1.6), 900) * np.exp(-T(0.09) / 0.03)


def scratch():
    n = N(0.13)
    return bp(rng.standard_normal(n), 1800, 5200) * np.sin(np.pi * np.linspace(0, 1, n)) ** 0.6 * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 95 * T(0.13))))


def honk(scale=1.0, soft=False):
    """A Canada goose, near enough: two syllables, low then high, reedy, through a nose.

    A narrow pulse (many harmonics, slow roll-off) for the reed, three band-passes for the
    formants that make it nasal rather than brassy, and a 48 Hz flutter for the rasp.
    """
    parts, gap = [(0.10, 292, 322), (0.30 if not soft else 0.2, 468, 384)], N(0.016)
    out = []
    for dur, fa, fb in parts:
        t = T(dur)
        f0 = (fa + (fb - fa) * (t / dur) ** 0.7) * scale * (1 + 0.006 * np.sin(2 * np.pi * 31 * t))
        ph = 2 * np.pi * np.cumsum(f0) / SR
        y = sum(np.sin(h * ph) / h ** 0.85 for h in range(1, int(9000 / (fa * scale)) + 1))
        env = (1 - np.exp(-t / 0.007)) * np.minimum(1, (dur - t) / 0.05) * (1 - 0.28 * (0.5 + 0.5 * np.sin(2 * np.pi * 48 * t)))
        out += [y * env, np.zeros(gap)]
    y = np.concatenate(out)
    y = 0.35 * lp(y, 900) + 1.0 * bp(y, 850, 1500) + 0.85 * bp(y, 1900, 2800) + 0.4 * bp(y, 3100, 3900)
    return (y + 0.04 * hp(rng.standard_normal(len(y)), 2500) * np.abs(y)) / np.max(np.abs(y))


def zipper(dur=0.75):
    """A zip: teeth, getting closer together and higher as it goes."""
    y, t = np.zeros(N(dur + 0.05)), 0.0
    while t < dur:
        k = t / dur
        c = click(1600 + 3200 * k, 0.0022) * (0.55 + 0.45 * k)
        y[N(t):N(t) + len(c)] += c[: len(y) - N(t)]
        t += 0.024 - 0.015 * k
    return y


def chirps(dur, f, rate, n_pulse=4, seed=0):
    """Crickets: a few pulses of a high note, again and again, never quite evenly."""
    r, y, t = np.random.default_rng(seed), np.zeros(N(dur)), 0.2
    pulse = np.sin(2 * np.pi * f * T(0.013)) * np.hanning(N(0.013))
    while t < dur - 0.3:
        for p in range(n_pulse):
            i = N(t + p * 0.027)
            y[i:i + len(pulse)] += pulse
        t += rate * r.uniform(0.85, 1.25)
    return y


def birds(dur, seed=3):
    r, y, t = np.random.default_rng(seed), np.zeros(N(dur)), 0.3
    while t < dur - 0.4:
        for p in range(r.integers(2, 5)):
            d, f0, f1 = r.uniform(0.05, 0.11), r.uniform(2600, 4200), r.uniform(3000, 5200)
            tt = T(d)
            c = np.sin(2 * np.pi * np.cumsum(f0 + (f1 - f0) * (tt / d) ** r.uniform(0.5, 2)) / SR) * np.hanning(len(tt))
            i = N(t + p * r.uniform(0.09, 0.16))
            y[i:i + len(c)] += c[: len(y) - i] * r.uniform(0.5, 1)
        t += r.uniform(0.55, 1.3)
    return y


def riser(dur):
    t = T(dur)
    x = (t / dur) ** 2.2
    tone = np.sin(2 * np.pi * np.cumsum(110 * 8 ** (t / dur)) / SR) * (1 + 0.5 * np.sin(2 * np.pi * np.cumsum(3 + 14 * x) / SR)) * 0.35
    return (sweep(rng.standard_normal(len(t)), 180, 7000, 1.6) * 1.2 + lp(tone, 3000)) * x


def boom():
    t = T(2.4)
    return np.sin(2 * np.pi * np.cumsum(30 + 34 * np.exp(-t / 0.09)) / SR) * np.exp(-t / 0.55) + 0.5 * lp(rng.standard_normal(len(t)), 220, 4) * np.exp(-t / 0.12)


def hum(dur, fall=0.4):
    """A bright screen in a dark room, as mains hum, with a power-down when it goes."""
    t = T(dur + fall)
    f = 100 * np.where(t < dur, 1.0, np.clip(1 - (t - dur) / fall, 0, 1) ** 1.5 * 0.7 + 0.3)
    ph = 2 * np.pi * np.cumsum(f) / SR
    y = sum(a * np.sin(h * ph) for h, a in ((1, 0.5), (2, 1), (3, 0.6), (5, 0.35), (7, 0.2)))
    return y * np.minimum(1, t / 0.5) * np.where(t < dur, 1.0, np.clip(1 - (t - dur) / fall, 0, 1)) * (1 + 0.1 * np.sin(2 * np.pi * 7.5 * t))


def impulse(rt, pre, bright, seed):
    r, n = np.random.default_rng(seed), N(rt * 1.3)
    t, chans = np.arange(n) / SR, []
    for _ in range(2):
        x = r.standard_normal(n) * np.exp(-6.9 * t / rt)
        chans.append(np.concatenate([np.zeros(N(pre)), lp(x, bright) * 0.6 + lp(x, 1400) * np.clip(t / rt, 0, 1)]))
    h = np.stack(chans, 1)
    return h / np.sqrt((h ** 2).sum(0).mean())


reverb = lambda x, h: np.stack([signal.fftconvolve(x[:, c], h[:, c])[: len(x)] for c in (0, 1)], 1)

# ---- harmony ---------------------------------------------------------------------------------
# name: (bass, what the pad and keys hold, what the marimba picks from)
CH = {
    "F": ("F2", ["F3", "A3", "C4", "F4"], ["F4", "A4", "C5", "F5"]),
    "C/E": ("E2", ["E3", "G3", "C4", "E4"], ["E4", "G4", "C5", "E5"]),
    "Dm7": ("D2", ["D3", "F3", "A3", "C4"], ["D4", "F4", "A4", "C5"]),
    "Bb": ("Bb2", ["Bb2", "D3", "F3", "A3"], ["D4", "F4", "A4", "Bb4"]),
    "Gm7": ("G2", ["G2", "Bb2", "D3", "F3"], ["D4", "F4", "G4", "Bb4"]),
    "Csus": ("C3", ["C3", "F3", "G3", "Bb3"], ["C4", "F4", "G4", "Bb4"]),
    "C": ("C3", ["C3", "E3", "G3", "C4"], ["C4", "E4", "G4", "C5"]),
    "Dm9": ("D2", ["D3", "A3", "E4", "F4"], ["A4", "D5", "E5", "F5"]),
}


def build(cues, duration, lines):
    at = lambda name, i=0: [c for c in cues if c["name"] == name][i]["t"]
    every = lambda name: [c for c in cues if c["name"] == name]
    mus, mus_wet, sfx, sfx_wet = (Bus(duration + 3) for _ in range(4))
    h = lambda: rng.uniform(-0.004, 0.004)  # nobody plays exactly on the grid

    def bar(t0, chord, level, beats=4, soft=1.0):
        """One bar of the groove at 120. level: 0 harmony only, 1 shaker and rim, 2 the kit."""
        root, hold, arp = CH[chord]
        held = pad(hold, beats * 0.5)
        mus.add(held, t0, 0.105 * soft)
        mus_wet.add(held, t0, 0.05 * soft)
        if level >= 1:
            for b, d in ((0, 0.8), (1.5, 0.35)):
                if b < beats:
                    for n in hold[1:]:
                        mus.add(keys(hz(n) * 2, d), t0 + b * 0.5 + h(), 0.05 * soft, rng.uniform(-0.3, 0.3))
            for i, (k, v) in enumerate(zip([0, 2, 1, 2, 3, 2, 1, 2], [1, .55, .75, .55, .9, .55, .75, .5])):
                if i < beats * 2:
                    m = mallet(hz(arp[k]))
                    mus.add(m, t0 + i * 0.25 + h(), 0.17 * v * soft, -0.25 + 0.12 * k)
                    mus_wet.add(m, t0 + i * 0.25, 0.05 * v * soft)
            for i in range(beats * 2):
                mus.add(shaker(1.0 if i % 2 == 0 else 0.55), t0 + i * 0.25 + h(), 0.12 * soft, 0.45)
            for b in (1, 3):
                if b < beats:
                    mus.add(rim(), t0 + b * 0.5 + 0.25, 0.06 * soft, -0.4)
        for b, d, up in ((0, 0.7, 1), (1.5, 0.2, 1), (2.5, 0.2, 1), (3, 0.4, 1.5 if level >= 2 else 1)):
            if b < beats and (level >= 1 or b == 0):
                mus.add(bass(hz(root) * up, d), t0 + b * 0.5, 0.21 * soft * (1 if b == 0 else 0.8))
        if level >= 2:
            for b in (0, 2, 2.75):
                if b < beats:
                    mus.add(kick(), t0 + b * 0.5, 0.36 * soft)
            for b in (1, 3):
                if b < beats:
                    s = snap()
                    mus.add(s, t0 + b * 0.5 + h(), 0.3 * soft, 0.15)
                    mus_wet.add(s, t0 + b * 0.5, 0.07 * soft)

    def ding(note, t, gain=0.12, pan=0.0, wet=0.08):
        mus.add(bell(hz(note)), t, gain, pan)
        mus_wet.add(bell(hz(note)), t, wet, pan)

    def tune(notes, t0, step, gain=0.2, pan=0.0):
        for i, n in enumerate(notes):
            if n:
                mus.add(mallet(hz(n), bright=1.2), t0 + i * step, gain, pan)
                mus_wet.add(mallet(hz(n)), t0 + i * step, gain * 0.35, pan)

    # ---- 0:00 a file, and then a great many dialogs ---------------------------------------------
    sfx.add(whoosh(0.35, 900, 300, 0, 0.3, 0.7), at("drop"), 0.25)
    sfx.add(thud(150), at("land"), 0.5, 0.3)
    sfx.add(flick(), at("land"), 0.2, 0.3)
    mus.add(pad(CH["F"][1], 1.9, attack=1.0), 0.4, 0.1)
    tune(["F5", "A5", "C6", None, "A5"], 0.9, 0.25, 0.15, 0.3)
    sfx.add(bloop(hz("C6"), 0.12, 1.3, 0.03), at("tap"), 0.25, 0.3)
    mus.add(pad(CH["Csus"][1], 3.4, attack=2.4, release=0.5), 2.4, 0.13)  # something is building
    for c in every("dialog"):
        p = bloop(hz(PENTA[c["i"]]), 0.22)
        x = 0.3 + 0.25 * np.sin(c["i"] * 2.4)
        sfx.add(p, c["t"], 0.3, x)
        sfx_wet.add(p, c["t"], 0.1, x)
    sfx.add(lp(rng.standard_normal(N(2.2)), 120, 4) * np.linspace(0, 1, N(2.2)) ** 2, at("dialog", 7) - 0.3, 0.55)

    # ---- 0:06 the goose -----------------------------------------------------------------------------
    rise = at("gooseRise")
    for i, n in enumerate(["F2", "A2", "C3", "F3"]):  # it comes up in four steps, on the beat
        mus.add(bass(hz(n) * 2, 0.16), rise + i * 0.25, 0.3, -0.2)
        mus.add(mallet(hz(n) * 4), rise + i * 0.25, 0.12, -0.2)
    sfx.add(whoosh(0.9, 250, 1600, -0.4, -0.1, 0.7), rise, 0.22)
    tune(["C6", "D6"], rise + 1.3, 0.12, 0.13, -0.2)  # hm?
    inhale = at("inhale")
    sfx.add(whoosh(0.42, 500, 3800, -0.2, -0.2, 0.92, 1.0), inhale, 0.4)  # and then nothing at all, for a moment
    hk = at("honk")
    sfx.add(honk(), hk, 0.62, -0.1)
    sfx_wet.add(honk(), hk, 0.2, -0.1)
    sfx.add(whoosh(1.0, 3500, 350, -0.3, 0.9, 0.22, 2.0), at("scatter") - 0.05, 0.7)
    for i in range(14):  # paper, leaving
        sfx.add(flick(), hk + 0.08 + i * 0.05 + rng.uniform(0, 0.03), 0.16 * (1 - i / 16), rng.uniform(0, 0.9))
    mus.add(kick(), hk, 0.6)
    mus.add(bass(hz("F2"), 0.9), hk, 0.34)

    # ---- 0:09 "Take a gander.", and the groove it has been waiting for ------------------------------
    g0 = hk + 1.0  # two beats of aftermath, then in on beat three
    bar(g0, "F", 2, beats=4)
    bar(g0 + 2.0, "Bb", 2, beats=2)
    bar(g0 + 3.0, "C", 1, beats=1)
    tune(["F5", "A5", "C6"], at("title") + 0.4, 0.25, 0.2, -0.3)
    ding("F6", at("title") + 0.9, 0.08)
    for c in every("turn"):
        sfx.add(whoosh(0.22, 1500, 3500, 0.2, -0.2, 0.5), c["t"] + 0.02, 0.13)
    sfx.add(whoosh(0.55, 1800, 300, -0.2, -0.2, 0.3), at("duck"), 0.2)
    sfx.add(whoosh(0.7, 300, 2600, 0.2, 0.4, 0.8, 1.8), at("morph"), 0.4)
    sfx.add(thud(110), at("phone") + 0.1, 0.35, 0.35)

    # ---- 0:13 nine kinds of file, each cut on a beat, each with its own small noise -----------------
    files = every("file")
    f0 = files[0]["t"]
    for i, chord in enumerate(["F", "C/E", "Dm7", "Bb", "F", "C/E", "Dm7"]):
        bar(f0 + i * 2.0, chord, 2)
    bar(f0 + 14.0, "Bb", 2, beats=2)
    bar(f0 + 15.0, "C", 2, beats=2)
    climb = dict(XLS="F5", PDF="G5", DOC="A5", PPT="C6", IMG="D6", VID="F6", MD="D6", ZIP="C6", HOME="F6")
    for c in files:
        t, k = c["t"], c["kind"]
        if t > f0:
            sfx.add(whoosh(0.3, 2600, 900, 0.7, 0.2, 0.35), t - 0.06, 0.17)
        if k in climb:  # the line climbs a step with every file
            tune([climb[k]], t, 0.25, 0.26, 0.35)
            ding(climb[k], t, 0.07, 0.35)
        if k == "XLS":
            for r in range(9):
                sfx.add(click(3000 + r * 120, 0.003), t + 0.05 + r * 0.035, 0.1, 0.35)
        if k == "PDF":
            sfx.add(whoosh(0.7, 500, 1100, 0.35, 0.35, 0.5, 1.0), t + 0.6, 0.12)
        if k == "DOC":
            for r in range(14):
                sfx.add(keyclick(), t + 0.08 + r * 0.045 + rng.uniform(0, 0.012), 0.09, 0.35)
        if k == "PPT":
            for r in range(3):
                sfx.add(whoosh(0.28, 900, 2400, 0.9, 0.3, 0.4), t + 0.03 + r * 0.11, 0.14)
        if k == "IMG":
            sfx.add(click(2200, 0.006), t + 0.1, 0.22, 0.35)
            sfx.add(click(1500, 0.009), t + 0.16, 0.22, 0.35)
        if k == "VID":
            sfx.add(bloop(hz("A5"), 0.1, 1.2, 0.03), t + 0.12, 0.15, 0.35)
        if k == "AUD":  # the phone is playing something, so for three beats the film lets it
            for j, n in enumerate(["C5", "D5", "F5", "G5", "A5", "G5", "F5", "D5", "F5", "G5", "A5", "C6"]):
                mus.add(lead(hz(n)), t + j * 0.125, 0.11, 0.4)
                mus_wet.add(lead(hz(n)), t + j * 0.125, 0.04, 0.4)
        if k == "MD":
            for r in range(5):
                sfx.add(keyclick(), t + 0.06 + r * 0.06, 0.09, 0.35)
            sfx.add(whoosh(0.6, 3000, 1200, 0.35, 0.35, 0.5, 0.8), t + 0.4, 0.08)
        if k == "ZIP":
            sfx.add(zipper(), t + 0.05, 0.3, 0.35)
    for i, c in enumerate(every("chip")):  # sixteen extensions, as popcorn, going up
        p = bloop(hz(PENTA[i]), 0.16, 1.5, 0.04)
        sfx.add(p, c["t"], 0.2, 0.2 + 0.6 * (i % 2))
        sfx_wet.add(p, c["t"], 0.07)

    # ---- 0:29 find ------------------------------------------------------------------------------------
    fd = at("find")
    bar(fd, "Gm7", 1)
    bar(fd + 2.0, "Csus", 1)
    bar(fd + 4.0, "C", 1, beats=2)
    sfx.add(whoosh(0.3, 2600, 900, 0.7, 0.2, 0.35), fd - 0.06, 0.17)
    for c in every("key"):
        sfx.add(keyclick(), c["t"], 0.2, 0.35)
    for c, n in zip(every("hit"), ["A5", "C6", "F6"]):  # three matches light up, a third apart
        ding(n, c["t"], 0.2, 0.35)
        sfx.add(click(3200, 0.004), c["t"], 0.26, 0.35)
    sfx.add(bloop(hz("F5"), 0.25, 0.6, 0.07), at("lens"), 0.3, -0.1)  # a bubble, upward
    ding("C7", at("lens") + 0.05, 0.04, -0.1)
    for c, n in zip(every("next"), ["G5", "A5"]):
        sfx.add(keyclick(), c["t"], 0.22, 0.35)
        tune([n], c["t"] + 0.05, 0.25, 0.16, 0.0)
    sfx.add(bloop(hz("C5"), 0.2, 1.8, 0.05), at("lensOut"), 0.2, -0.1)

    # ---- 0:34 a photo, and the camera going into it and not stopping ------------------------------------
    ph = at("photo")
    bar(ph, "F", 2)
    bar(ph + 2.0, "Bb", 0, beats=1)
    sfx.add(whoosh(0.3, 2600, 900, 0.7, 0.2, 0.35), ph - 0.06, 0.17)
    pinch = [c for c in cues if c["name"] == "pinch"][0]
    tp = T(pinch["dur"])
    sfx.add(np.sin(2 * np.pi * np.cumsum(330 * 2 ** (tp / pinch["dur"])) / SR) * np.sin(np.pi * tp / pinch["dur"]) ** 0.7, pinch["t"], 0.09, 0.35)
    push = [c for c in cues if c["name"] == "push"][0]
    mus.add(pad(CH["Bb"][1], push["dur"], attack=0.3, release=0.05), push["t"], 0.12)
    sfx.add(riser(push["dur"]), push["t"], 0.5)
    sfx.add(boom(), at("dark"), 0.6)
    sfx_wet.add(boom(), at("dark"), 0.25)

    # ---- 0:39 2am: D minor, crickets, and a page that is far too bright -------------------------------
    nt, nm = at("night"), [c for c in cues if c["name"] == "nightMode"][0]
    mus.add(pad(CH["Dm9"][1], 3.0, attack=1.2), nt, 0.15)
    mus_wet.add(pad(CH["Dm9"][1], 3.0, attack=1.2), nt, 0.1)
    mus.add(bass(hz("D2"), 2.4), nt, 0.22)
    for i, n in enumerate(["A4", "D5", "F5", "E5"]):
        mus.add(keys(hz(n), 0.9), nt + 0.5 + i * 0.5, 0.14, 0.1)
        mus_wet.add(keys(hz(n), 0.9), nt + 0.5 + i * 0.5, 0.08)
    glare = [c for c in cues if c["name"] == "glare"][0]
    sfx.add(hum(glare["dur"]), glare["t"], 0.05, 0.35)
    for i in range(12):  # the page turns over, and the light comes down the scale with it
        ding(PENTA[13 - i], nm["t"] + i * nm["dur"] / 12, 0.035, 0.35, 0.05)
    res = nt + 3.0  # where the wipe lands, the harmony lets go
    mus.add(pad(CH["Bb"][1], 3.0, attack=0.5), res, 0.15)
    mus_wet.add(pad(CH["Bb"][1], 3.0), res, 0.1)
    mus.add(bass(hz("Bb2"), 2.4), res, 0.22)
    for i, n in enumerate(["D5", "F5", "A5", "G5", "F5"]):
        mus.add(keys(hz(n), 0.9), res + 0.5 + i * 0.5, 0.13, 0.1)
        mus_wet.add(keys(hz(n), 0.9), res + 0.5 + i * 0.5, 0.08)
    dawn = [c for c in cues if c["name"] == "dawn"][0]
    night_len = dawn["t"] - nt + 0.6
    fade = np.minimum(1, T(night_len) / 1.5) * np.minimum(1, (night_len - T(night_len)) / 1.2)
    sfx.add(chirps(night_len, 4300, 0.62, 4, 1) * fade, nt + 0.1, 0.035, -0.6)
    sfx.add(chirps(night_len, 3900, 0.81, 3, 2) * fade, nt + 0.3, 0.028, 0.7)

    # ---- 0:45 takes nothing ------------------------------------------------------------------------------
    no = at("nothing")
    for i, chord in enumerate(["Dm7", "Bb", "Gm7", "Csus"]):
        bar(no + i * 2.0, chord, 1, soft=0.8)
    bar(no + 8.0, "F", 2, soft=0.85)
    for c in every("bubble"):
        sfx.add(bloop(hz(PENTA[3 + c["i"]]), 0.2, 0.6, 0.06), c["t"], 0.2, -0.5 if c["i"] < 3 else 0.8)
    for c in every("strike"):  # a line through it, and it is gone: six of them across one bar, as triplets
        x = -0.5 if c["i"] < 3 else 0.8
        sfx.add(scratch(), c["t"], 0.2, x)
        sfx.add(bloop(hz(PENTA[8 - c["i"]]), 0.22, 2.2, 0.05), c["t"] + 0.16, 0.24, x)
    sfx.add(whoosh(1.2, 2500, 5000, -0.5, -0.5, 0.5, 1.0), at("cloud"), 0.07)
    for i in range(7):  # something trying to get out
        sfx.add(bloop(hz(PENTA[6 + i % 4]), 0.07, 1.0, 0.02), at("cloud") + 0.45 + i * 0.125, 0.07, 0.1 - i * 0.08)
    for c in every("bump"):
        sfx.add(thud(120 + 12 * c["i"]), c["t"], 0.3, 0.35)
    tune(["A3", "F3"], at("cut"), 0.18, 0.3, -0.2)  # no
    off = [c for c in cues if c["name"] == "cloudOff"][0]
    sfx.add(whoosh(off["dur"], 4000, 900, -0.4, -1.0, 0.3, 1.0), off["t"], 0.1)
    for c, n in zip(every("check"), ["F5", "A5", "C6", "F6"]):
        ding(n, c["t"], 0.15, 0.35)
        sfx.add(click(2600, 0.004), c["t"], 0.2, 0.35)

    # ---- 0:55 morning -------------------------------------------------------------------------------------
    d = dawn["t"]
    mus.add(pad(["F2", "C3", "F3", "A3", "C4", "F4"], 2.6, attack=1.4), d, 0.26)
    mus_wet.add(pad(["F3", "A3", "C4", "F4"], 2.6, attack=1.4), d, 0.12)
    sfx.add(whoosh(1.5, 300, 5000, 0, 0, 0.85, 2.2), d - 0.3, 0.22)
    sfx.add(birds(6.0) * np.minimum(1, T(6.0) / 1.5) * np.minimum(1, (6.0 - T(6.0)) / 1.0), d + 0.6, 0.05, 0.3)
    for c, chord in zip(every("fact"), ["F", "Bb", "C"]):  # three plain facts, three plain chords
        root, hold, arp = CH[chord]
        mus.add(kick(), c["t"], 0.45)
        mus.add(bass(hz(root), 0.6), c["t"], 0.32)
        for n in hold:
            mus.add(keys(hz(n) * 2, 0.7), c["t"], 0.075, rng.uniform(-0.4, 0.4))
        ding(arp[3], c["t"], 0.1)
        mus.add(pad(hold, 0.8, attack=0.05, release=0.6), c["t"], 0.12)
    last = every("fact")[-1]["t"]
    end = at("end")
    mus.add(pad(CH["Csus"][1], end - last - 0.9, attack=0.2, release=0.4), last + 0.8, 0.14)
    run = ["F4", "G4", "A4", "C5", "D5", "F5", "G5", "A5", "C6", "D6"]
    for i, n in enumerate(run):  # a run up to the end card, getting quicker
        tune([n], end - 1.0 + (i / len(run)) ** 0.8, 0.25, 0.1 + 0.012 * i, 0.0)

    # ---- 1:00 the icon, and the name -----------------------------------------------------------------------
    bar(end, "F", 2)
    bar(end + 2.0, "Bb", 2, beats=2)
    bar(end + 3.0, "C", 2, beats=2)
    sfx.add(bloop(hz("F4"), 0.25, 0.55, 0.08), at("tile"), 0.3)
    for c in every("fan"):
        sfx.add(flick(), c["t"], 0.22, -0.3 + 0.3 * c["i"])
    up = [c for c in cues if c["name"] == "gooseUp"][0]
    for i, n in enumerate(["F3", "A3", "C4"]):
        mus.add(mallet(hz(n) * 2), up["t"] + i * 0.2, 0.13)
    tune(["F5", "A5", "C6"], at("wordmark"), 0.25, 0.26)  # the three notes the film opened with
    ding("F6", at("wordmark") + 0.5, 0.12)
    fin = end + 4.0
    mus.add(pad(["F2", "C3", "F3", "A3", "C4", "F4"], 1.3, attack=0.03, release=0.9), fin, 0.28)
    mus_wet.add(pad(CH["F"][1], 1.0, attack=0.03), fin, 0.14)
    mus.add(kick(), fin, 0.5)
    mus.add(bass(hz("F2"), 1.2), fin, 0.34)
    ding("C6", fin, 0.12)
    st = [c for c in cues if c["name"] == "stretch"][0]
    ts = T(st["dur"])
    sfx.add(np.sin(2 * np.pi * np.cumsum(260 * 2 ** (ts / st["dur"])) / SR) * np.sin(np.pi * ts / st["dur"]) ** 0.6, st["t"], 0.08)
    sfx.add(honk(1.12, soft=True), at("lastHonk"), 0.3)  # smaller, and rather pleased with itself
    sfx_wet.add(honk(1.12, soft=True), at("lastHonk"), 0.14)
    tune(["F6"], end + 5.45, 0.25, 0.14)

    hall, room = impulse(2.1, 0.02, 5200, 11), impulse(0.7, 0.008, 6500, 12)
    music = mus.a + reverb(mus_wet.a, hall) * 0.9
    music = music - 0.3 * lp(music, 120) + 0.9 * hp(music, 3500)
    sound = sfx.a + reverb(sfx_wet.a, room) * 0.8

    # ---- the narration ---------------------------------------------------------------------------------------
    vo = Bus(duration + 3)
    for ln in lines:
        rate, y = wavfile.read(OUT / "voice" / f"{ln['id']}.wav")
        y = y.astype(np.float64) / (2 ** 31 if y.dtype == np.int32 else 2 ** 15 if y.dtype == np.int16 else 1)
        y = hp(signal.resample_poly(y, SR, rate), 85)
        y = y / (np.sqrt(np.mean(y ** 2)) + 1e-9) * 0.2    # every line at the same level
        y = y + 0.22 * bp(y, 2500, 6000)                    # and a little forward, to sit over a marimba
        vo.add(y, ln["at"])
    voice = vo.a + reverb(vo.a, room) * 0.06
    return music, sound, voice


def duck(voice, depth_db, attack=0.06, release=0.35):
    """How far everything else steps back while somebody is talking."""
    env = uniform_filter1d(np.abs(voice).max(1), N(0.05))
    talking = (env > 0.004).astype(float)
    talking = np.maximum(talking, np.roll(talking, -N(attack)))  # start stepping back just before the word
    sm = uniform_filter1d(uniform_filter1d(talking, N(attack)), N(release))
    return (10 ** (-depth_db * np.clip(sm * 1.4, 0, 1) / 20))[:, None]


def limit(x, ceiling=0.76):
    need = np.minimum(1.0, ceiling / np.maximum(np.abs(x).max(1), 1e-9))
    g = uniform_filter1d(minimum_filter1d(need, 2 * N(0.004) + 1), N(0.004))
    return x * g[:, None]


def loudness(path):
    err = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True).stderr
    tail = err[err.rfind("Summary:"):]
    num = lambda pat: float(re.search(pat, tail).group(1))
    return {"lufs": num(r"I:\s+(-?[\d.]+) LUFS"), "range": num(r"LRA:\s+(-?[\d.]+) LU"), "peak": num(r"Peak:\s+(-?[\d.]+) dBFS")}


def master(x, path, duration, target=-14.0):
    x = hp(x[: N(duration)], 28)
    x[-N(0.45):] *= np.linspace(1, 0, N(0.45))[:, None] ** 2
    for _ in range(2):  # gain to the target, limit, and once more for what the limiter took
        wavfile.write(path, SR, x.astype(np.float32))
        x = limit(x * 10 ** ((target - loudness(path)["lufs"]) / 20))
    wavfile.write(path, SR, x.astype(np.float32))
    m = loudness(path)
    if m["peak"] > -1.5:  # true peak, which a sample-peak limiter cannot see, and AAC adds a little more
        x *= 10 ** ((-1.5 - m["peak"]) / 20)
        wavfile.write(path, SR, x.astype(np.float32))
        m = loudness(path)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    film = json.loads((OUT / "cues.json").read_text())
    lines = json.loads((OUT / "voice" / "lines.json").read_text())["lines"]
    music, sound, voice = build(film["cues"], film["duration"], lines)

    bed = music * duck(voice, 12.0) + sound * duck(voice, 6.0)
    report = {
        "with voice": master(bed + voice, OUT / "soundtrack.wav", film["duration"]),
        "no voice": master(music + sound, OUT / "soundtrack-no-voice.wav", film["duration"]),
    }
    for name, stem in (("music", music), ("sfx", sound), ("voice", voice)):
        wavfile.write(OUT / f"stem-{name}.wav", SR, stem[: N(film["duration"])].astype(np.float32))

    # While somebody is talking, how far above everything else are they?
    talking = uniform_filter1d(np.abs(voice).max(1), N(0.05)) > 0.004
    rms = lambda a: 20 * np.log10(np.sqrt(np.mean(a[talking] ** 2)) + 1e-12)
    report["voice over bed, dB"] = round(rms(voice) - rms(bed), 1)
    (OUT / "soundtrack-report.json").write_text(json.dumps(report, indent=1) + "\n")
    for k, v in report.items():
        print(f"{k}: {v}")


if __name__ == "__main__":
    main()
