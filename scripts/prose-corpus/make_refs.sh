#!/bin/zsh
# LibreOffice's reading of every corpus file, as the reference the harness scores
# against: the text (reftxt/) and, for looking at, a PDF and a PNG of its first two
# pages (refs/). One argument, the work directory that fetch_wild.py wrote into.
S="$1"
LO="-env:UserInstallation=file:///tmp/gander-lo-profile-refs"
for set in gen wild/doc wild/rtf wild/odt; do
  files=("$S/corpus/$set"/*(.N))
  [[ ${#files} -eq 0 ]] && continue
  mkdir -p "$S/refs/$set" "$S/reftxt/$set"
  soffice "$LO" --headless --convert-to pdf --outdir "$S/refs/$set" "${files[@]}" >/dev/null 2>&1
  soffice "$LO" --headless --convert-to "txt:Text (encoded):UTF8" --outdir "$S/reftxt/$set" "${files[@]}" >/dev/null 2>&1
  for pdf in "$S/refs/$set"/*.pdf(.N); do
    gs -q -dNOPAUSE -dBATCH -sDEVICE=png16m -r60 -dFirstPage=1 -dLastPage=2 \
       -sOutputFile="${pdf:r}-p%d.png" "$pdf" >/dev/null 2>&1
  done
  echo "$set: $(ls "$S/reftxt/$set"/*.txt 2>/dev/null | wc -l | tr -d ' ') references for ${#files} files"
done
