# Promo video: upload notes

Output: `out/gander-promo.mp4`, 1920x1080, 60 fps, H.264 + AAC. Cover on Play is the
feature graphic (`../upload/featureGraphic.jpg`), by Play's own rule, so the video opens on
the same ground and the same type it leaves from.

## YouTube

Play needs a plain YouTube URL: public or unlisted (not private), ads off, not age-restricted,
no timecode parameters. Google's page: support.google.com/googleplay/android-developer/answer/9866151

- Title: `Gander for Android: take a gander at any file`
- Description:

  ```
  Gander is an offline file viewer for Android. PDF, Word, Excel, PowerPoint, photos,
  video, audio, Markdown and code, in one small app that asks for no permissions,
  has no internet access, no ads and no trackers.

  Free and open source under the MIT licence:
  https://github.com/mokshablr/gander
  https://arjun.maniyani.com/gander/
  ```

- Visibility: Unlisted. Monetisation: off. Audience: not made for kids. Category: Science & Technology.
- Thumbnail: `out/thumbnail.jpg` (1280x720, the end card).
- Once uploaded, paste `https://www.youtube.com/watch?v=...` into Play Console under
  Store presence > Main store listing > Video.

## Music

The bed under the video is synthesised by `music.py`, so there is nothing to license or credit.
To swap in a different track, drop a file next to it and re-run `compose.py build` with
`GANDER_BED=/path/to/track.wav`.

## Rebuilding

```
python3 layers.py            # ground, frame, captions (edit CAPTIONS there)
python3 shoot.py all         # re-record every beat from the emulator
python3 compose.py sheet     # contact sheets, to pick cut points
python3 compose.py build     # segments -> screen.mp4 -> out/gander-promo.mp4
```
