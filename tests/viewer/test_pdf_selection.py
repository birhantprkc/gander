"""
padRows() in pdf.mjs: the hit boxes a finger drags a selection through. Issue #22.

pdf.js sizes a text span to its glyphs, so a line box is the height of the type and
no wider than the words on it. The leading between lines, the white beside a short
line, the gutter between two columns and the page margins therefore belong to no
span at all. Every span is absolutely positioned, so the layer itself has nothing in
flow, and when a touch lands outside every span Blink asks the container instead and
answers the same position wherever it is asked. That is the freeze in issue #22: the
selection stops following the finger and then jumps a heading or a page.

padRows() pads the spans until their boxes tile the page. So the property under test
is not "mostly covered", it is covered, and the check for it is exact rather than
sampled: merge every span's vertical interval and any gap left over is a hole a drag
can die in. A sampling grid is what missed this the first time - 9,604 points on a
1000px page steps about 14px and walks straight over a 6px hole.

**Everything here runs twice, and the second run is the one that matters.** A span
carries `scaleX(--scale-x) scale(1 / --min-font-size)`, and on desktop Chrome
`--min-font-size` is 1, which makes the second factor 1 and hides every mistake in
it. Android's WebView refuses to draw text below 8px, pdf.js measures that floor and
lays each span out 8x too large to compensate, and only then does a padding written
without dividing by that scale come out 8x short. Chromium's `minimumFontSize` blink
setting reproduces it exactly. Measured with the scale correction removed: ordinary
Chrome reports the same 0.03px worst hole either way, while under the floor the worst
hole goes to 100px and coverage falls to 43%. **A test that only runs unfloored is
vacuous for this bug.** The unfloored run still earns its place: it catches padRows
being broken or dropped altogether, which takes coverage to about 64% in both.
"""

from typing import NamedTuple

import pytest
from playwright.sync_api import BrowserContext

from helpers import wait_for_pdf, wait_for_text_layer

# What Android's WebView does to every page it renders, and desktop Chrome does not.
WEBVIEW_FONT_FLOOR = "--blink-settings=minimumFontSize=8,minimumLogicalFontSize=8"

# Rounding, and nothing else. Paddings are written with two decimals and the browser
# lays out on a subpixel grid, which together account for ~0.03px. The failure this
# guards against is three orders of magnitude larger.
ROUNDING = 0.1


# The vertical intervals of every span in a layer, merged. Whatever is left between
# two merged runs is a horizontal band inside the page that no span covers.
HOLES_DOWN_THE_PAGE = """() => {
  const layer = document.querySelector('#pages .pg .textLayer');
  const top = layer.getBoundingClientRect().top;
  const iv = [...layer.querySelectorAll('span')]
    .map(s => s.getBoundingClientRect()).filter(r => r.height > 0)
    .map(r => [r.top, r.bottom]).sort((a, b) => a[0] - b[0]);
  const merged = [iv[0].slice()];
  for (const [from, to] of iv.slice(1)) {
    const last = merged[merged.length - 1];
    if (from <= last[1]) last[1] = Math.max(last[1], to);
    else merged.push([from, to]);
  }
  const holes = [];
  for (let i = 1; i < merged.length; i++)
    holes.push({ at: +(merged[i - 1][1] - top).toFixed(1),
                 size: +(merged[i][0] - merged[i - 1][1]).toFixed(3) });
  return holes.sort((a, b) => b.size - a.size);
}"""

# The same question across a row rather than down the page: group the spans that
# share a line, then walk them left to right looking for daylight between them, and
# from the page edges to the ends of the row.
#
# Rows are grouped the way padRows groups them, by walking spans in top order and
# starting a new row when one begins more than a tolerance below the last. Bucketing
# by a rounded top instead would put two spans on the same line either side of a
# bucket boundary into different rows, and then report the whole width between them
# as a hole that is not there.
HOLES_ACROSS_THE_ROWS = """() => {
  const el = document.querySelector('#pages .pg .textLayer');
  const layer = el.getBoundingClientRect();
  const boxes = [...el.querySelectorAll('span')]
    .filter(s => !parseFloat(s.style.getPropertyValue('--rotate')))
    .map(s => s.getBoundingClientRect()).filter(r => r.height > 0)
    .sort((a, b) => a.top - b.top || a.left - b.left);

  const rows = [];
  let row = null;
  for (const r of boxes) {
    if (!row || r.top - row.top > 2) rows.push(row = { top: r.top, boxes: [r] });
    else row.boxes.push(r);
  }

  const holes = [];
  for (const { boxes: line } of rows) {
    line.sort((a, b) => a.left - b.left);
    holes.push({ where: 'left margin', size: +(line[0].left - layer.left).toFixed(3) });
    for (let i = 1; i < line.length; i++)
      holes.push({ where: 'between spans on a line',
                   size: +(line[i].left - line[i - 1].right).toFixed(3) });
    holes.push({ where: 'right margin',
                 size: +(layer.right - line[line.length - 1].right).toFixed(3) });
  }
  return holes.sort((a, b) => b.size - a.size);
}"""

# Where the words actually are, read off the text node so that padding on the span
# cannot flatter the answer. Called once as shipped and once with all padding
# stripped, which is pdf.js's own placement with nothing of ours on top of it.
WORD_POSITIONS = """() => [...document.querySelectorAll('#pages .pg .textLayer span')]
  .filter(s => s.firstChild && s.firstChild.nodeType === 3)
  .map(s => { const r = document.createRange(); r.selectNodeContents(s);
              const b = r.getBoundingClientRect(); return [b.top, b.left]; })"""

STRIP_PADDING = """() => {
  for (const s of document.querySelectorAll('#pages .pg .textLayer span')) {
    s.style.paddingTop = s.style.paddingBottom = s.style.paddingLeft =
      s.style.paddingRight = s.style.marginTop = s.style.marginLeft = '';
  }
}"""


class Engine(NamedTuple):
    context: BrowserContext
    floors_fonts: bool
    # What pdf.js should measure the floor to be. 8 is Blink's own default minimum
    # and the value WebView ships; 1 means no floor, which is desktop Chrome.
    expected_min_font_size: float


@pytest.fixture(scope="module", params=[False, True],
                ids=["chrome", "webview-font-floor"])
def engine(request, browser_type, browser_type_launch_args, browser_context_args):
    """
    The page twice over: as desktop Chrome renders it, and as Android's WebView does.

    Parametrised rather than written out twice because the assertions are the same
    ones; what changes is whether `--min-font-size` is 1 or 8, and that is the whole
    difference between a run that can see this class of bug and one that cannot.

    Launched here rather than through the shared `browser` fixture because a blink
    setting can only be given at launch. `browser_type_launch_args` is carried
    through and added to rather than replaced, so `--headed`, `--slowmo` and the rest
    of pytest-playwright's options still reach these two browsers like any other.
    """
    floors_fonts = request.param
    launch_args = dict(browser_type_launch_args)
    if floors_fonts:
        launch_args["args"] = [*launch_args.get("args", []), WEBVIEW_FONT_FLOOR]

    browser = browser_type.launch(**launch_args)
    context = browser.new_context(**browser_context_args)
    try:
        yield Engine(context, floors_fonts, 8.0 if floors_fonts else 1.0)
    finally:
        context.close()
        browser.close()


@pytest.fixture
def shown(engine, server):
    """Opens a fixture in this run's engine and waits for its words to arrive."""
    pages = []

    def open_doc(document="ragged-prose.pdf"):
        server.show(document)
        page = engine.context.new_page()
        pages.append(page)
        page.goto(server.url("pdf.html"))
        page.wait_for_load_state("domcontentloaded")
        wait_for_pdf(page)
        wait_for_text_layer(page)
        return page

    try:
        yield open_doc
    finally:
        for page in pages:
            page.close()


def test_the_webview_font_floor_is_reproduced(shown, engine):
    """
    The guard on everything below. `--min-font-size` is what pdf.js measured the
    engine's floor to be, and it is the factor a padding has to be divided by. If
    the floored run ever reads 1, the blink setting stopped being applied and every
    other test in this file quietly stopped being able to fail.
    """
    page = shown()
    # The inline value, which is the one pdf.js measured. The stylesheet also
    # declares a --min-font-size, so reading a computed style here would answer 1
    # from the CSS default even if pdf.js had stopped writing one.
    written = page.evaluate(
        "() => document.querySelector('.textLayer')"
        ".style.getPropertyValue('--min-font-size')")
    assert written, "pdf.js wrote no --min-font-size on the text layer at all"

    assert float(written) == engine.expected_min_font_size, (
        f"expected --min-font-size {engine.expected_min_font_size} with the floor "
        f"{'on' if engine.floors_fonts else 'off'}, pdf.js measured {written}")


def test_a_finger_dragged_down_the_page_always_has_a_word_under_it(shown):
    """Issue #22 itself: a band with no span in it is where the drag freezes."""
    page = shown()
    holes = page.evaluate(HOLES_DOWN_THE_PAGE)
    worst = holes[0] if holes else {"size": 0}
    assert worst["size"] < ROUNDING, (
        f"{len(holes)} uncovered bands, worst {worst['size']}px "
        f"at y={worst.get('at')} in the page")


def test_a_row_is_covered_from_one_margin_to_the_other(shown):
    """
    The half that kept sections failing after paragraphs were fixed. A heading
    reaches a sixth of the way across, so a finger dragged down the middle of the
    measure passes through the white beside it; the same goes for a column gutter.
    """
    page = shown()
    holes = page.evaluate(HOLES_ACROSS_THE_ROWS)
    worst = holes[0] if holes else {"size": 0}
    assert worst["size"] < ROUNDING, (
        f"widest gap across a row is {worst['size']}px, {worst.get('where')}")


def test_the_padding_leaves_the_words_where_pdf_js_put_them(shown):
    """
    Padding grows a box away from an edge that is already positioned, and negative
    margins put the box's own edge back, so the glyphs must not move at all. They
    are compared against the page with every padding stripped, which is pdf.js's
    placement with nothing of ours on it. Getting this wrong lifts a line off the
    picture it is meant to sit on, worst on the line after a wide blank, because the
    error is a fraction of the padding rather than a fixed amount.
    """
    page = shown()
    shipped = page.evaluate(WORD_POSITIONS)
    page.evaluate(STRIP_PADDING)
    pristine = page.evaluate(WORD_POSITIONS)
    assert len(shipped) == len(pristine) and shipped

    drift = max(max(abs(a[0] - b[0]), abs(a[1] - b[1]))
                for a, b in zip(shipped, pristine))
    assert drift < ROUNDING, f"padding moved a word by {drift:.3f}px"


def test_text_printed_sideways_is_left_alone(shown):
    """
    padRows reads horizontal lines out of vertical positions, which means nothing on
    a rotated span, so it skips them one at a time rather than abandoning the page.
    The fixture has one sideways line and the rest of it still comes out covered,
    which the two coverage tests above assert on the same document.
    """
    page = shown()
    padded = page.evaluate(
        """() => [...document.querySelectorAll('#pages .pg .textLayer span')]
             .filter(s => parseFloat(s.style.getPropertyValue('--rotate')))
             .map(s => s.style.paddingTop + s.style.paddingBottom + s.style.paddingRight)
             .filter(Boolean)"""
    )
    assert padded == [], f"rotated spans were padded: {padded}"


def test_the_margin_between_two_pages_is_bridged(shown):
    """
    The strip between two pages is .pg's own margin, so it lies inside no text layer
    and a selection dragged across it used to run away to a whole page. .vw-gap is a
    sibling of the text layer that fills it.
    """
    page = shown("six-pages.pdf")
    seam = page.evaluate(
        """() => {
          const pages = [...document.querySelectorAll('#pages .pg')];
          const a = pages[0].getBoundingClientRect();
          const b = pages[1].getBoundingClientRect();
          const gap = pages[0].querySelector('.vw-gap');
          if (!gap) return null;
          const g = gap.getBoundingClientRect();
          return { above: +(g.top - a.bottom).toFixed(2),
                   below: +(b.top - g.bottom).toFixed(2) };
        }"""
    )
    assert seam is not None, "no .vw-gap under the first page"
    assert abs(seam["above"]) < ROUNDING and abs(seam["below"]) < ROUNDING, (
        f"the bridge does not meet the pages either side: {seam}")
