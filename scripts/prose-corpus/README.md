# Measuring the prose readers

`prose-odt.js`, `prose-rtf.js` and `prose-doc.js` are Gander's own readers for
OpenDocument text, Rich Text and Word 97-2003. The viewer tests prove the page around
them with three hand-written fixtures; what proves the readers is real files, scored
against LibreOffice's reading of the same files. This directory is that measurement.
Run it before trusting a change to a reader, and run it on any library proposed in place
of one, with an adapter of its own.

Needs `gh`, LibreOffice (`soffice` on the path), Ghostscript, pandoc, `textutil` (macOS),
and the Python the viewer tests use (Playwright, python-docx, Pillow).

```sh
W=/tmp/prose-corpus                         # anywhere outside the repo
python3 scripts/prose-corpus/fetch_wild.py "$W"   # POI and Tika test files: ~150, 15 MB
scripts/prose-corpus/make_gen.sh "$W"             # one document through three writers
scripts/prose-corpus/make_refs.sh "$W"            # LibreOffice's text and pages of each
python3 scripts/prose-corpus/harness.py "$W" doc ours-doc --shots
python3 scripts/prose-corpus/harness.py "$W" odt ours-odt --shots
python3 scripts/prose-corpus/harness.py "$W" rtf ours-rtf --shots
```

The score is word recall and precision against the reference text. Recall is how much of
the document reached the page; precision is how much of what reached the page is the
document's. Precision is the softer number: LibreOffice's text export leaves out headers
and footers, so a reader that draws them on every sheet is charged for it, and a picture
the page cannot draw shows a sentence saying so. Look at the screenshots beside the
numbers, against the `refs/` pages.

On 2026-09-22, when the readers were written, over 91 `.doc`, 45 `.rtf` and 18 `.odt`
files: recall 0.955, 1.000 and 0.931, the last held down by an encrypted file that is
refused on purpose. The three best libraries found on npm, run through the same harness
on the same day, scored 0.841 (`@file-viewer/doc` 3.1.2), 0.871 (`rtf.js`) and 0.900
(`odf-kit` 0.14.3).
