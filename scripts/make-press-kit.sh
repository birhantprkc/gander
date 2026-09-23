#!/usr/bin/env bash
# Rebuilds docs/press/assets/gander-press-kit.zip from the loose assets beside it.
#
# The ZIP is a convenience bundle of files that are already committed individually,
# so it is gitignored and built here rather than versioned: committing it would put
# a second 3.5 MB copy of the same pictures into history on every regeneration, and
# a ZIP does not delta-compress between versions.
#
# Run it after editing any asset or README.txt, then re-upload the whole
# docs/press/ tree to cPanel. The size is printed so the "all of it as one ZIP"
# line in docs/press/index.html can be kept honest.
#
# The file list is explicit and in the same order as README.txt, so a new asset
# has to be added in both places deliberately.

set -euo pipefail

cd "$(dirname "$0")/.."
ASSETS="docs/press/assets"
OUT="$ASSETS/gander-press-kit.zip"

FILES=(
  README.txt
  gander-icon-512.png
  gander-wordmark.svg
  gander-wordmark-inverse.svg
  gander-wordmark-1200.png
  gander-wordmark-1200-inverse.png
  gander-permissions.png
  gander-home.png
  gander-pdf.png
  gander-pdf-night.png
  gander-word.png
  gander-spreadsheet.png
  gander-find-in-document.png
  gander-feature-graphic.jpg
)

for f in "${FILES[@]}"; do
  [ -f "$ASSETS/$f" ] || { echo "missing: $ASSETS/$f" >&2; exit 1; }
done

# Build to a temporary name and move into place, so a failure leaves the previous
# ZIP intact and so zip never merges into an existing archive.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

( cd "$ASSETS" && zip -X -q "$TMP/kit.zip" "${FILES[@]}" )
mv "$TMP/kit.zip" "$OUT"

# wc rather than stat: GNU stat reads -f as file system mode and %z as a second file,
# so it printed the file system's details for $OUT before the fallback added the size.
BYTES=$(wc -c < "$OUT" | tr -d ' ')
printf '%s\n' "$OUT"
printf '  %s files, %s bytes (%.1f MB)\n' "${#FILES[@]}" "$BYTES" "$(echo "$BYTES / 1000000" | bc -l)"
printf '  update the ZIP size in docs/press/index.html if that number moved\n'
