"""
Fetches the real-world files the readers are measured on: Word 97-2003 files from the
Apache POI test corpus, and Rich Text and OpenDocument files from Apache Tika's, all
of them written by Word, LibreOffice, OpenOffice and the odd fuzzer. Needs `gh`.

    python3 fetch_wild.py <work>

writes <work>/corpus/wild/{doc,rtf,odt}/.
"""

import json, subprocess, sys, urllib.parse, urllib.request
from pathlib import Path

S = Path(sys.argv[1]) / "corpus"

def listing(repo, path):
    out = subprocess.run(["gh", "api", f"repos/{repo}/contents/{path}", "--paginate"],
                         capture_output=True, text=True, check=True).stdout
    # --paginate concatenates arrays
    items = []
    dec = json.JSONDecoder()
    i = 0
    while i < len(out):
        while i < len(out) and out[i].isspace():
            i += 1
        if i >= len(out):
            break
        obj, j = dec.raw_decode(out, i)
        items.extend(obj)
        i = j
    return items

def fetch(item, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size == item["size"]:
        return
    url = item["download_url"]
    with urllib.request.urlopen(url, timeout=60) as r:
        dest.write_bytes(r.read())

POI = "apache/poi", "test-data/document"
DOC_WANT = """SampleDoc.doc simple.doc test.doc test2.doc Lists.doc simple-list.doc lists-margins.doc
simple-table.doc table-merges.doc innertable.doc footnote.doc endingnote.doc hyperlink.doc test-fields.doc
pageref.doc HeaderFooterUnicode.doc ThreeColHeadFoot.doc FancyFoot.doc picture.doc PngPicture.doc
two_images.doc testPictures.doc pictures_escher.doc vector_image.doc FloatingPictures.doc Word6.doc
Word95.doc word2.doc PasswordProtected.doc password_tika_binaryrc4.doc password_password_cryptoapi.doc
o_kurs.doc ob_is.doc rasp.doc GaiaTest.doc equation.doc watermark.doc WithArtShapes.doc
57603-seven_columns.doc page-break.doc Bug46220.doc 58804.doc Fuzzed.doc 51921-Word-Crash067.doc empty.doc
saved-by-table.doc MarkAuthorsTable.doc header_image.doc word_with_embeded.doc Bug44431.doc
documentProperties.doc capitalized.doc 47304.doc Bug47742.doc""".split()

n = 0
for item in listing(*POI):
    name = item["name"]
    keep = name in DOC_WANT or name.startswith(("au.edu", "ca.kwsymphony", "cap.stanford", "cn.orthodox"))
    if name.startswith("clusterfuzz") and name.lower().endswith(".doc") and item["size"] < 20000:
        keep = True
    if keep and name.lower().endswith(".doc"):
        safe = name.replace("&", "_").replace("=", "_")
        fetch(item, S / "wild/doc" / safe)
        n += 1
print("doc", n)

T1 = "apache/tika", "tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents"
n = 0
for item in listing(*T1):
    name = item["name"]
    if name.lower().endswith(".rtf") and item["size"] < 1_500_000:
        fetch(item, S / "wild/rtf" / name); n += 1
    if name.lower().endswith(".doc") and name.startswith("testWORD") and item["size"] < 600_000:
        fetch(item, S / "wild/doc" / ("tika-" + name)); n += 1
print("tika ms", n)

T2 = "apache/tika", "tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-miscoffice-module/src/test/resources/test-documents"
n = 0
for item in listing(*T2):
    name = item["name"]
    if name.lower().endswith((".odt", ".fodt", ".ott", ".sxw")):
        fetch(item, S / "wild/odt" / name); n += 1
print("tika odf", n)
