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
```

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
real difference fails. The upload a 4K file gets from YouTube is a better 1080p stream than
a 1080p file gets, which is what `--uhd` is for.

**The hand-drawn line is geometry, not a filter.** An SVG displacement filter moves pixels,
which leaves every near-straight edge as an unantialiased staircase. `F.wob()` instead
samples a path into points once, and eight times a second pushes them through seeded noise
and writes the path back. Eight, not sixty: a line that is redrawn every frame shimmers, and
one that is held for a few frames looks traced.

**The goose is the launcher icon.** Its head is the outline from `ic_launcher_foreground.xml`
moved so the origin is the top of the neck, and split at the beak so it can open. The neck
is rebuilt each frame as a ribbon along a curve. A scene animates a base, a head position and
a few numbers (`tilt`, `bend`, `open`, `blink`, `brow`, `flip`) and the rig does the rest. The
end card puts the same rig over three file cards at the icon's own coordinates, which is
why it lands on the real icon rather than on a drawing of it.

**The colours are Gander's.** The two grounds are the launcher's cream and the store
listing's warm near-black; the accents are the site's red and the listing's coral; the format
colours are `WELCOME_BADGES` in `Listing.kt`, and the pastel behind each file is that format's
colour let down into the cream. A new format colour in the app should be changed here too.

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

## What it claims

Film copy is a claim surface like any other. It says **about 5 MB**, **free**, **open source**
and **MIT licensed**; that Gander requests **no permissions** and has **no internet access**,
**no ads, trackers, analytics or accounts**; and it shows PDF, Word, Excel, PowerPoint, photos,
video, audio, Markdown and `.zip`, find in document, deep zoom and night mode for PDFs. If
one of those stops being true, the film has to change with it.

It has no soundtrack. It is built to be read, and works with the sound off, which is how
most places will play it.
