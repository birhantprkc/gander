#!/bin/sh
# Put a soundtrack on a rendered film without touching the picture.
#   audio/mux.sh out/gander-film.mp4 out/soundtrack.wav out/gander-film-sound.mp4
set -eu
ffmpeg -y -loglevel error -i "$1" -i "$2" -map 0:v:0 -map 1:a:0 -c:v copy \
  -c:a aac -b:a 256k -ar 48000 -movflags +faststart -shortest "$3"
echo "$3"
