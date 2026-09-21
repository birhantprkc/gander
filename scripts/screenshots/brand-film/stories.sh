#!/bin/sh
# Instagram cuts a Story every 60 seconds, wherever that falls. This film is 66, and its end
# card begins at 60.0 exactly, on a bar line of the score, so cut it there on purpose: the
# first Story is the film, and the second is the end card on its own, which is where a link
# sticker wants to go anyway.
#
#   ./stories.sh out/gander-film-tall-sound.mp4 out/gander-story
#
# Nothing is re-encoded, which is only exact because the film was rendered with
# `--keyframes 60`. Without a keyframe there the second part would start on the nearest one
# before it, and show the last of the sunrise first.
set -eu
ffmpeg -y -loglevel error -i "$1" -t 60 -c copy -movflags +faststart "$2-1-film.mp4"
ffmpeg -y -loglevel error -ss 60 -i "$1" -c copy -movflags +faststart "$2-2-end-card.mp4"
echo "$2-1-film.mp4"
echo "$2-2-end-card.mp4"
