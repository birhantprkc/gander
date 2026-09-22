"""prose.html: OpenDocument text, Rich Text and Word 97-2003, read by Gander's own readers.

The three fixtures carry the same short document, written by hand in
make_fixtures.py, so most of what is checked here is checked three times over.
What they prove is the page: that the bytes choose the reader, that text arrives in
order with its formatting, that tables, pictures, notes and page breaks come through,
and that a file none of the readers recognises gets the card. How faithfully each
reader follows its format was measured against LibreOffice over real files when
they were written; a fixture from the same hand cannot measure that.
"""

import pytest

from helpers import status_text, status_visible

FIXTURES = ["letter.odt", "memo.rtf", "legacy.doc"]


def wait_for_document(page, timeout=20000):
    page.wait_for_function(
        "() => document.querySelector('.vw-paper > section') && "
        "document.querySelector('#container').textContent.includes('ordering is checkable')",
        timeout=timeout,
    )


def paper_text(page):
    return page.evaluate("() => document.querySelector('#container').innerText")


@pytest.mark.parametrize("fixture", FIXTURES)
def test_the_text_arrives_in_order(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    text = paper_text(page)
    assert text.index("Field Survey, Willowmere") < text.index("A short report") \
        < text.index("Bold words") < text.index("Surveying") < text.index("ordering is checkable")


@pytest.mark.parametrize("fixture", FIXTURES)
def test_the_card_goes_when_the_document_is_up(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    page.wait_for_function(
        "() => getComputedStyle(document.getElementById('vw-status')).display === 'none'",
        timeout=10000,
    )
    assert not status_visible(page)


@pytest.mark.parametrize("fixture", FIXTURES)
def test_a_bold_run_is_bold_and_the_words_around_it_are_not(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    weights = page.evaluate(
        "() => { const out = {};"
        "  for (const el of document.querySelectorAll('.vw-paper p, .vw-paper p span')) {"
        "    const t = el.textContent;"
        "    if (t === 'Bold words') out.bold = getComputedStyle(el).fontWeight;"
        "    if (t.startsWith('A short report')) out.plain = getComputedStyle(el).fontWeight;"
        "  } return out; }"
    )
    assert weights["bold"] in ("700", "bold")
    assert weights["plain"] in ("400", "normal")


@pytest.mark.parametrize("fixture", FIXTURES)
def test_a_table_is_a_table(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    cells = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper td')].map(td => td.textContent.trim())"
    )
    assert cells == ["Item", "Amount", "Surveying", "4200"]


@pytest.mark.parametrize("fixture", FIXTURES)
def test_text_outside_latin_comes_through(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    assert "\u092a\u0941\u0932 \u092c\u0902\u0926 \u0939\u0948\u0964" in paper_text(page)


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_a_page_break_starts_a_new_sheet(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    sheets = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section')]"
        ".map(s => s.querySelector('.vw-body').textContent.includes('ordering is checkable'))"
    )
    assert sheets == [False, True]


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_the_header_is_on_every_sheet(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    headers = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section .vw-header')].map(h => h.textContent.trim())"
    )
    assert headers == ["HEADER-MARK Willowmere Parish Council"] * 2


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_a_footnote_is_marked_in_the_text_and_set_under_a_rule(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    assert page.text_content(".vw-noteref").strip() == "1"
    assert "A footnote, which lands under a rule at the end." in page.text_content(".vw-notes")


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_a_picture_is_drawn_from_bytes_the_page_holds(viewer, page, fixture, server):
    viewer("prose.html", fixture)
    wait_for_document(page)
    page.wait_for_function(
        "() => { const i = document.querySelector('.vw-paper img');"
        "return i && i.complete && i.naturalWidth > 0; }",
        timeout=10000,
    )
    src = page.get_attribute(".vw-paper img", "src")
    assert src.startswith("blob:")
    # The document was the only thing fetched: no picture, font or anything else
    # was asked for from outside the page
    assert [r["path"] for r in server.full_requests()] == ["/doc/file." + fixture.split(".")[-1]]


@pytest.mark.parametrize("fixture", ["letter.odt", "memo.rtf"])
def test_a_list_item_carries_its_bullet_as_text(viewer, page, fixture):
    viewer("prose.html", fixture)
    wait_for_document(page)
    labels = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper .vw-label')].map(l => l.textContent.trim())"
    )
    assert labels[:2] == ["\u2022", "\u2022"]


def test_a_symbol_font_bullet_becomes_the_character_it_stands_for(viewer, page):
    """
    memo.rtf writes its bullets as Wingdings 0xB7, which on a phone without the font
    is a box or a middle dot. The list label above is that byte, turned into a bullet.
    """
    viewer("prose.html", "memo.rtf")
    wait_for_document(page)
    assert "\uf0b7" not in paper_text(page)
    assert "\u00b7" not in page.text_content(".vw-label")


def test_the_bytes_choose_the_reader_and_the_name_does_not(viewer, page, server):
    """A .doc that is Rich Text inside, which for twenty years was the easy way to
    make a file Word would open, reads as Rich Text."""
    from server import FIXTURES as FILES
    server.show(FILES / "memo.rtf")
    page.goto(server.url("prose.html", name="notes.doc", ext="doc"))
    wait_for_document(page)
    assert "HEADER-MARK" in paper_text(page)


def test_a_file_none_of_the_readers_recognises_gets_the_card(viewer, page):
    viewer("prose.html", "unknown.xyz")
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "Could not open this document" in said
    assert "OpenDocument, Rich Text or Word" in said


def test_the_sheet_is_the_paper_size_the_file_gives(viewer, page):
    """letter.odt asks for A4 with 2 cm margins; the sheet is that, in points."""
    viewer("prose.html", "letter.odt")
    wait_for_document(page)
    width, left = page.evaluate(
        "() => { const s = document.querySelector('.vw-paper > section');"
        "return [parseFloat(s.style.width), parseFloat(s.style.paddingLeft)]; }"
    )
    assert abs(width - 595.28) < 0.01 and abs(left - 56.69) < 0.01


def test_the_sheets_are_scaled_to_fill_the_width(viewer, page):
    viewer("prose.html", "memo.rtf")
    wait_for_document(page)
    page.wait_for_function(
        "() => document.querySelector('.vw-paper').style.getPropertyValue('--vw-page-zoom') !== ''"
    )
    zoom = float(page.evaluate(
        "() => document.querySelector('.vw-paper').style.getPropertyValue('--vw-page-zoom')"
    ))
    # 980 CSS px of layout, less the wrapper's padding, over an A4 sheet 794 px wide
    assert 1.1 < zoom < 1.3


def test_nothing_in_a_document_becomes_markup(viewer, page, made):
    """
    Text is put in as text and formatting through the style object, so a file that
    carries markup in its text, or a stylesheet-ending string in a font name, changes
    nothing about the page. Checked on the format whose text is easiest to write.
    """
    hostile = (
        b"{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\fswiss Arial\";} body { display:none } .x{;}}"
        b"\\pard\\plain\\f0 <img src=x onerror=alert(1)> and a </style><script>window.__pwned=1</script>"
        b"\\par}"
    )
    viewer("prose.html", made("hostile.rtf", hostile))
    page.wait_for_function(
        "() => document.querySelector('#container').textContent.includes('onerror')", timeout=15000
    )
    assert page.evaluate("() => window.__pwned") is None
    assert page.evaluate("() => document.querySelectorAll('#container img, #container script').length") == 0
    assert page.evaluate("() => getComputedStyle(document.body).display") != "none"
