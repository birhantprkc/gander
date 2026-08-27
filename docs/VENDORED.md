# Vendored viewer libraries

Gander renders Office formats, Markdown and spreadsheets with open source
JavaScript libraries bundled under `app/src/main/assets/viewer/lib/`. They are
vendored (not fetched at runtime) because the app has no network access at all.

`scripts/fetch-viewer-libs.sh` re-downloads every file below from its upstream.

This table is the provenance record. The *shipped* notice is
`app/src/main/assets/licences.md`, reachable from the app's About dialog, and it
carries the full licence texts because Apache-2.0 §4(a) and the MIT and BSD
notice clauses require them to travel with the binary rather than sit in a repo.
Adding, dropping or upgrading a library here means editing that file in the same
commit.

| File | Project | Version | License | Upstream |
| --- | --- | --- | --- | --- |
| `pdf.min.mjs` | pdf.js (legacy build) | 5.7.284 | Apache-2.0 | https://github.com/mozilla/pdf.js |
| `pdf.worker.min.mjs` | pdf.js worker (legacy build) | 5.7.284 | Apache-2.0 | https://github.com/mozilla/pdf.js |
| `jszip3.min.js` | JSZip | 3.10.1 | MIT or GPL-3.0 dual | https://github.com/Stuk/jszip |
| `docx-preview.min.js` | docx-preview | 0.3.x (jsdelivr latest, fetched 2026-07-19) | Apache-2.0 | https://github.com/VolodymyrBaydalka/docxjs |
| `xlsx.full.min.js` | SheetJS Community Edition | 0.20.3 | Apache-2.0 | https://git.sheetjs.com/sheetjs/sheetjs |
| `marked.min.js` | marked | 15.0.12 | MIT | https://github.com/markedjs/marked |
| `purify.min.js` | DOMPurify | 3.4.12 | Apache-2.0 or MPL-2.0 dual | https://github.com/cure53/DOMPurify |
| `pptx/pptxjs.js` | PPTXjs | 1.21.1 | MIT | https://github.com/meshesha/PPTXjs |
| `pptx/divs2slides.js` | divs2slides (PPTXjs) | 1.3.2 | MIT | https://github.com/meshesha/PPTXjs |
| `pptx/filereader.js` | FileReader.js (PPTXjs bundle) | 0.99 | MIT | https://github.com/meshesha/PPTXjs |
| `pptx/jquery.min.js` | jQuery | 1.11.3 | MIT | https://github.com/jquery/jquery |
| `pptx/jszip2.min.js` | JSZip 2.x (PPTXjs bundle) | 2.x | MIT or GPL-3.0 dual | https://github.com/meshesha/PPTXjs |
| `pptx/d3.min.js` | D3 | 3.5.10 | BSD-3-Clause | https://github.com/d3/d3 |
| `pptx/nv.d3.min.js` | NVD3 | 1.8.1 | Apache-2.0 | https://github.com/novus/nvd3 |
| `pptx/pptxjs.css`, `pptx/nv.d3.min.css` | PPTXjs / NVD3 styles | see above | see above | see above |

## Before upgrading pdf.js

`pdf.html` is the only viewer loaded as an ES module, and a module the WebView
cannot parse never runs, so the page has no way to report the problem from inside
itself. That makes the Chromium floor a fact to check rather than a preference.

- The legacy build of 5.7.284 supports **Chromium 125 and newer** (Mozilla's
  pdf.js FAQ). That number is `PDFJS_MIN_CHROMIUM_MAJOR` in
  `app/src/main/java/com/arjun/gander/ViewerActivity.kt`, compared against the
  WebView package actually in use. Below it, `pdf.html` shows a card explaining
  that Android System WebView needs updating instead of loading the renderer.
- **Chromium 138 is the ceiling on Android 8.0, 8.1 and 9.0.** Chromium 139
  requires Android 10, so those releases will never receive a newer WebView, and
  minSdk here is 26. A pdf.js version needing more than 138 therefore does not
  degrade on API 26 to 28, it ends PDF support there. Check the new version's
  floor first: if it is above 138, this is a decision about dropping PDFs on
  Android 8 and 9, not a version bump.

- **The text layer CSS in `pdf.html` is a contract, and it fails silently.** pdf.js
  writes only `left`, `top`, `font-family` and three custom properties on each span:
  `--font-height`, `--scale-x` and `--rotate`. It never writes `font-size` or
  `transform`; the stylesheet has to turn those properties into both, and the scale
  hook in 5.7.284 is `--total-scale-factor` (the older `--scale-factor` is not read).
  Get any of it wrong and nothing throws and nothing looks broken, because the text is
  transparent and the picture underneath is still correct. It shows up only as a
  selection covering the wrong words or a search highlight stopping short of the word
  it found. The upstream rules are in `web/pdf_viewer.css` of the same version; diff
  the `.textLayer` block against the one in `pdf.html` on any upgrade.

Bumping pdf.js means editing together the two `pdf.*.mjs` rows above, `PDFJS` in
`scripts/fetch-viewer-libs.sh`, and `PDFJS_MIN_CHROMIUM_MAJOR`. The card's wording
lives in `pdf.html` and reads both version numbers out of the query string, so it
needs no edit. It also reads `locked`, which says the reader has no way to update
the WebView and selects wording that does not ask them to; that flag is about the
phone rather than about pdf.js, so a version bump does not affect it either.

Notes for packagers (F-Droid and friends): the minified files are unmodified
upstream distribution artifacts. If unminified sources are required, every
project above publishes them at the linked repository, and the fetch script can
be pointed at the unminified dist files where upstream provides them.
