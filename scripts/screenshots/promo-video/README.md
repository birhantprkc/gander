# Promo video build sources

The generators behind the Play listing's promo video: the ground, device frame and
captions (`layers.py`), the emulator capture pass (`shoot.py`), the synthesised music
bed (`music.py`) and the edit that assembles them (`compose.py`). `fragment.py` renders
the one-off permissions caption. `UPLOAD.md` holds the YouTube and Play Console settings.

Only the sources are here. The working data is not committed and lives under
`docs/screenshots/v1.14/video/`, which is gitignored: `raw/` holds the captures,
`seg/` the cut segments, `layers/` the rendered overlays and `out/` the finished
`gander-promo.mp4` and its thumbnail. Together they run to about 176 MB.

Two dependencies are not committed:

- **`Jost.ttf`.** `layers.py` reads it from beside itself. It is not in the repo for the
  same reason `scripts/make-wordmark.py` leaves it out, that the font is not ours to
  redistribute. Fetch it into this directory first:

      curl -L -o Jost.ttf \
        'https://github.com/google/fonts/raw/main/ofl/jost/Jost%5Bwght%5D.ttf'

- **The captures.** `shoot.py all` re-records every beat from the emulator. Read the
  capture gotchas in `../store-art/README.md` before a rerun, particularly the debug
  resource overlay and the tablet AVD's `-gpu host` requirement.

Recording the emulator has one trap of its own: `adb shell screenrecord` goes silent
when `ViewerActivity` is created mid-clip, so a beat that opens a document records as
nothing. `adb emu screenrecord` survives it but draws the hole-punch cutout, which is
why the pipeline uses both and cuts between them.
