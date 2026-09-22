#!/bin/zsh
# The one document every reader should draw the same, written out by three programs:
# LibreOffice, macOS's own Cocoa text system, and pandoc. <work>/corpus/gen/.
S="$1"
here="${0:a:h}"
mkdir -p "$S/corpus/src" "$S/corpus/gen"
python3 "$here/make_master.py" "$S/corpus/src"
LO="-env:UserInstallation=file:///tmp/gander-lo-profile-gen"
for fmt in odt rtf 'doc:MS Word 97'; do
  soffice "$LO" --headless --convert-to "$fmt" --outdir "$S/corpus/gen" "$S/corpus/src/master.docx" >/dev/null 2>&1
  e="${fmt%%:*}"; mv "$S/corpus/gen/master.$e" "$S/corpus/gen/lo-master-$e.$e"
done
for fmt in rtf doc odt; do textutil -convert $fmt "$S/corpus/src/master.docx" -output "$S/corpus/gen/cocoa-master-$fmt.$fmt"; done
pandoc "$S/corpus/src/master.docx" -s -o "$S/corpus/gen/pandoc-master-rtf.rtf"
pandoc "$S/corpus/src/master.docx" -o "$S/corpus/gen/pandoc-master-odt.odt"
ls "$S/corpus/gen"
