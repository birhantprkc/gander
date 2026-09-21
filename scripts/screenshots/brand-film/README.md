# The Gander film

A 66 second illustrated film about what Gander does, 1920x1080 at 60 fps. Nothing in it is
recorded: every frame is drawn by the page in this directory, so the whole film rebuilds
from source with one command, at any resolution.

It is drawn rather than shot because it is about the idea of the app, not its pixels. The
Play listing video next door in `listing-video/` is the opposite: real footage, because its
argument is a screen you can go and check.

## Run it

```sh
# once: the typeface (SIL OFL, fetched rather than committed)
mkdir -p fonts
curl -L -o fonts/Jost.ttf        'https://github.com/google/fonts/raw/main/ofl/jost/Jost%5Bwght%5D.ttf'
curl -L -o fonts/Jost-Italic.ttf 'https://github.com/google/fonts/raw/main/ofl/jost/Jost-Italic%5Bwght%5D.ttf'

open film.html                          # watch it live: space plays, arrows step a frame, shift+arrows a second
node render.mjs --check                 # before any real render: see "How it works"
node render.mjs --scale 2 --uhd         # the masters: drawn at 4K, written at 1080p and at 2160p
node render.mjs                         # a quicker one, drawn at 1080p
node render.mjs --from 28.9 --to 34     # part of it
node render.mjs --stills 8.52,47.6      # single frames into out/stills/
node render.mjs --sheet 0,13,16         # sixteen small frames from 0s to 13s as one picture

open 'film.html?tall'                   # the phone cut, live
node render.mjs --tall --scale 2 --keyframes 60   # the phone cut: 1080x1920 at 60 fps, for Instagram
```

`--tall` goes with any of the others (`--tall --check`, `--tall --stills 47.6`).

Needs Node 22 (for its built-in WebSocket), Google Chrome and ffmpeg, and nothing from npm.
`CHROME=/path/to/chrome` points it at a different browser. `out/` is gitignored.

## How it works

**Every frame is a pure function of its time.** There is no CSS animation, no timer and no
state carried from one frame to the next: `F.renderAt(seconds)` sets every attribute on the
page from that one number. That is what lets `render.mjs` ask headless Chrome for frames one
at a time over the DevTools protocol, in any order, across six tabs at once, and get exactly
what the player shows. If you add something that animates, make it a function of `t`. A
`setTimeout` or a CSS transition will look fine in the player and tear in the render.

`--check` holds the film to that. Two freshly loaded tabs draw the same 179 times, one
forwards and one backwards, and every pair has to match. It exists because the rule was
broken once without anybody noticing: a scene added breathing to the pose it was handed,
the pose it was handed was the keyframe itself, and every frame drawn bent the track for the
next, so the goose lost its neck only in a render, and only in some orders. A few frames of
large type differ by a level or two in a few pixels whatever you do (that is Chrome's glyph
rasteriser, not the film), so a mismatch is measured rather than just counted, and only a
real difference fails.

`--uhd` also writes the frames out at 2160p, for anywhere that re-encodes what it is given:
YouTube makes a better 1080p stream from a 4K upload than from a 1080p one.

**The hand-drawn line is geometry, not a filter.** An SVG displacement filter moves pixels,
which leaves every near-straight edge as an unantialiased staircase. `F.wob()` instead
samples a path into points once, and eight times a second pushes them through seeded noise
and writes the path back. Eight, not sixty: a line that is redrawn every frame shimmers, and
one that is held for a few frames looks traced.

**The goose is the launcher icon.** Its head is the outline from `ic_launcher_foreground.xml`
moved so the origin is the top of the neck, and split at the beak so it can open. The neck
is rebuilt each frame as a ribbon along a curve. A scene animates a base, a head position and
a few numbers (`tilt`, `bend`, `open`, `blink`, `brow`, `flip`) and the rig does the rest.
When it turns, the profile narrows over a front view of the head that is as wide as the neck,
because a head on a neck is never narrower than the neck: narrowing it over nothing opened a
notch of background at the chin and left a sliver on a post at the midpoint. The
end card puts the same rig over three file cards at the icon's own coordinates, which is
why it lands on the real icon rather than on a drawing of it.

**The colours are Gander's.** The two grounds are the launcher's cream and the store
listing's warm near-black; the accents are the site's red and the listing's coral; the format
colours are `WELCOME_BADGES` in `Listing.kt`, and the pastel behind each file is that format's
colour let down into the cream. A new format colour in the app should be changed here too.

## The phone cut

The same film at 1080x1920, for Reels and Stories. It is not the wide film with bars, and it
is not a second film: `?tall` re-seats the one there is. Every scene is still drawn in the
wide film's coordinates, and `F.seat()` picks its illustration and its type up whole and sets
them down again, type above and picture below. Only what cannot simply be moved asks
`F.TALL`: the goose in the opening comes up in front of the pile because on a phone there is
no beside; the cloud sits next to the phone instead of above it, where the two lines under
"Takes nothing." now have to go, broken into four; the stars are scattered over whichever
stage this is before being put into the picture's coordinates. The timeline is identical, so
`out/cues.json` and the soundtrack are the same ones and `audio/mux.sh` puts them on as is.

Instagram covers about the top 250 px with its own furniture and the bottom 340 with the
caption, so the type starts under the one and the phone ends above the other, and the bare
band at the foot of every frame is theirs, not an oversight.

60 fps, like the wide film. It was first rendered at 30 on the reasoning that Instagram plays
30 and would only throw the rest away, which was an assumption about somebody else's
transcoder stated as a fact, and the wrong way round even if true: since a frame here is just
a time, `--fps 30` draws exactly every other frame of this, so a clean halving of the 60 loses
nothing that 30 would have had, and everything that does play 60 gets it. Give a platform the
best master and let it do its own worst. `./stories.sh` then cuts the result at 60.0 s without
re-encoding, which is where Instagram would cut a Story anyway and, not by accident, where the
end card begins.

## The files

| | |
| --- | --- |
| `film.html` | The page. Opened plainly it is a player; with `?render` it waits to be stepped. |
| `render.mjs` | Drives Chrome, writes frames, runs ffmpeg. Converts to BT.709 explicitly, or the coral shifts. |
| `src/core.js` | Easing, keyframes, the seeded random, blobs, the masked headline. |
| `src/style.js` | Palette, the hand-drawn wobble, the paper grain, the icon set. |
| `src/parts.js` | Badge, file card, phone, dialog. |
| `src/goose.js` | The rig. |
| `src/scenes/open.js` | 0:00 A file arrives, twelve dialogs bury it, and the goose honks them away. |
| `src/scenes/formats.js` | What the phone shows for each kind of file, and the landscape they share. |
| `src/scenes/viewer.js` | 0:12.7 One phone in one place: the parade of formats. |
| `src/scenes/acts.js` | 0:28.9 Find, deep zoom, night mode, and "Takes nothing." on that same phone. |
| `src/scenes/close.js` | 0:55.2 Three facts at sunrise, and the end card. |
| `audio/voice.py` | The narration, one line per cue, from Kokoro. `--check` transcribes it back. |
| `audio/score.py` | Every instrument and effect (numpy only), the score, the mix and the master. |
| `audio/check.py` | Sound on every cue, and the script still intelligible through the full mix. |
| `audio/octaves.py` | Energy per octave, for anything that will be heard on a phone. |
| `audio/mux.sh` | Puts a soundtrack on a rendered film without re-encoding the picture. |
| `stories.sh` | Cuts the phone cut at 60 s, where Instagram would, which is where the end card starts. |

## What it claims

Film copy is a claim surface like any other, and the dearest one to correct: a film cannot
be edited in place, and a second upload is a second URL. So it carries **no size and no
version**, the same decision as screenshot 6 and the feature graphic, and says "Tiny." where
a number would go. It says **free**, **open source**
and **MIT licensed**; that Gander requests **no permissions** and has **no internet access**,
**no ads, trackers, analytics or accounts**; and it shows PDF, Word, Excel, PowerPoint, photos,
video, audio, Markdown and `.zip`, find in document, deep zoom and night mode for PDFs. If
one of those stops being true, the film has to change with it.

It is built to be read, and works with the sound off, which is how most places will first
play it. The soundtrack is for everybody who then turns it on.

## Sound

```sh
node render.mjs --cues                      # out/cues.json: when everything in the film happens
.venv/bin/python audio/voice.py --phonemes  # read this first: how each line will be pronounced
.venv/bin/python audio/voice.py --check     # the narration, and a recogniser's opinion of it
python3 audio/score.py                      # score, sound design and mix: out/soundtrack.wav
.venv/bin/python audio/check.py             # what can be checked without ears
python3 audio/octaves.py out/*.wav          # where the energy is, octave by octave
audio/mux.sh out/gander-film.mp4 out/soundtrack.wav out/gander-film-sound.mp4
```

**The soundtrack is cut to the film's own timeline, not to a copy of it.** Scenes call
`F.cue(time, name)` with the same constant that moves the picture, `--cues` writes them out,
and nothing in `audio/` contains a time. Move a scene and its sounds move with it; rename a
cue and `check.py` reports the silence.

**It is written to picture, which is the point of it.** Music laid under a film sounds like
music laid under a film. Here every dialog that pops up is the next note of a pentatonic
scale, and they arrive as quarters, then eighths, then sixteenths of the score's own bar; the
format cuts fall on its beats, three at a time against four; the honk gets a bar of silence
to land in; the zip opens with a zip; the phone plays a tune when it opens an audio file, and
the goose nods to it at the tempo of the score, which is 120; the night is in D minor with
crickets and a too-bright page humming, and the morning is back in F with birds. The three
notes under "Take a gander." at the start are the three under the wordmark at the end.

**The narrator is Kokoro**, an 82M-parameter model whose weights are Apache-2.0, run locally.
Not the Mac's `say` voices: Apple licenses those for personal, non-commercial use. Setup:

```sh
python3 -m venv .venv && .venv/bin/pip install kokoro-onnx soundfile faster-whisper
mkdir -p models && cd models
curl -LO https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx
curl -LO https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
```

**Read the phonemes; do not rely on the recogniser for pronunciation.** A recogniser writes
"PDFs" whether it heard pee-dee-effs or pee-dee-eff-ess, and "2am" whether it heard two-ay-em
or two-uh-em. The first cut of this narration said the wrong one of each, passed every check,
and was caught by a person listening. `--phonemes` prints what the model is about to be given,
which is text and can be read. Both mistakes were respellings meant to help, so the rule is to
give the model the plain line unless the phonemes show it going wrong, and then to give it
phonemes (between slashes in `LINES`) rather than a cleverer spelling.

`audio/voice.py --voice bm_george` (or any Kokoro voice) changes the narrator;
`out/soundtrack-no-voice.wav` is the same mix without one.

**It was mixed by measurement, because it was written by something that cannot hear.** That
is worth knowing before trusting it, and it is why the checks exist. The voice sits 11.6 dB
over everything else while it speaks (the first mix had it at 0.3); a speech recogniser given
the *finished mix* returns all 78 words of the script; every one of the 81 cues that should be
a transient has a sound starting on it; the master is -14.6 LUFS at -1.5 dB true peak; and 76%
of the mix's energy is between 250 Hz and 4 kHz, where a phone speaker lives (the first pass
of the music had half of its energy below that and 3% above 2 kHz). None of that says it
sounds good. That takes a person with ears, and theirs is the opinion that counts.
