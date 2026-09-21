"""docx.html: docx-preview, plus the private-use bullet fix."""

from helpers import status_text


def wait_for_document(page, timeout=25000):
    page.wait_for_function(
        "() => document.querySelector('#container') && "
        "document.querySelector('#container').textContent.trim().length > 0",
        timeout=timeout,
    )


def test_a_document_renders_its_text(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    assert "Field Survey, Willowmere" in page.text_content("#container")


def test_paragraphs_keep_their_order(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    text = page.text_content("#container")
    assert text.index("A short report") < text.index("The paragraph after it")


def test_a_wingdings_bullet_becomes_a_real_character(viewer, page):
    """
    Word writes its bullets as private use codepoints in a font that is not on
    the phone, so they arrive as blank boxes. fixSymbolChars swaps the range
    U+F000 to U+F0FF for the Unicode characters they stand for.
    """
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    page.wait_for_timeout(500)

    leftover = page.evaluate(
        "() => { const t = document.querySelector('#container').textContent;"
        "return [...t].filter(c => c >= '\\uF000' && c <= '\\uF0FF').length; }"
    )
    assert leftover == 0, f"{leftover} private use characters left in the document"


def test_the_text_around_the_bullet_is_untouched(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    assert "A bullet that arrives as a private use codepoint." \
        in page.text_content("#container")


# ---------------------------------------------------------------------------
# The too-old-WebView card, which Kotlin decides and app.js words
# ---------------------------------------------------------------------------

def test_an_engine_too_old_for_docx_preview_is_told_to_update_it(viewer, page, server):
    viewer("docx.html", "report.docx", webview=64, needs=80)
    page.wait_for_selector(".vw-error", timeout=15000)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Word documents" in said
    assert "64" in said and "80" in said
    assert "updating android system webview" in said.lower()
    # The card is the whole page: the document is never read
    assert server.full_requests() == []


def test_the_card_is_all_that_shows_when_docx_preview_cannot_parse(viewer, page):
    """
    Issue #31, WebView 64. docx-preview could not parse, the page rendered anyway,
    and the reader was told "docx is not defined". The browser here parses it, so
    the file is swapped for one that no engine can.
    """
    page.route(
        "**/assets/viewer/lib/docx-preview.min.js",
        lambda route: route.fulfill(content_type="text/javascript", body="var x = ;"),
    )
    with page.expect_event("pageerror"):
        viewer("docx.html", "report.docx", webview=64, needs=80)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Word documents" in said
    assert "not defined" not in said


def test_a_locked_engine_is_not_told_to_update_what_it_cannot(viewer, page):
    viewer("docx.html", "report.docx", webview=64, needs=80, locked=1)
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "Word documents cannot be shown" in said
    assert "Updating Android System WebView" not in said
