"""
Reopening a PDF at the page it was left at, issue #25, and Go to page, which puts a page on
screen the same way.

The page readout is what Kotlin saves, so a page put on screen and then read back as another
does not just say the wrong number: the document opens a page further on every time.
"""

import time

import pytest

from helpers import wait_for_pdf


@pytest.fixture(scope="session")
def turned_pdf(tmp_path_factory):
    """A portrait cover, then eleven landscape pages: a report with its charts turned."""
    from reportlab.lib.pagesizes import A4, landscape
    from reportlab.pdfgen import canvas

    target = tmp_path_factory.mktemp("turned") / "turned.pdf"
    c = canvas.Canvas(str(target), pagesize=A4, invariant=1)
    c.drawString(72, 760, "Cover")
    c.showPage()
    for n in range(2, 13):
        c.setPageSize(landscape(A4))
        c.drawString(72, 500, f"Chart on page {n}")
        c.showPage()
    c.save()
    return target


def settled_page(page, port, timeout=5.0):
    """The last page the readout was told, once it has stopped changing."""
    deadline = time.time() + timeout
    previous, since = None, time.time()
    while time.time() < deadline:
        pages = port.pages()
        current = pages[-1] if pages else None
        if current != previous:
            previous, since = current, time.time()
        elif current is not None and time.time() - since > 0.5:
            return current
        page.wait_for_timeout(50)
    return previous


def nudge(page):
    """The smallest move a reader makes, which is when the readout stops naming the page asked for."""
    page.evaluate("() => window.scrollBy(0, 2)")


def test_a_reopened_document_reads_as_the_page_it_was_left_at(viewer, page, port, turned_pdf):
    viewer("pdf.html", turned_pdf, resume=5)
    wait_for_pdf(page)
    p = port()
    p.wait_for(r"page 5 12")
    nudge(page)
    assert settled_page(page, p) == "page 5 12"


def test_a_landscape_page_after_a_portrait_one_is_reopened_where_it_can_be_read_as_itself(
        viewer, page, port, turned_pdf):
    """
    The page asked for is placed from its own shape, not from the first page's, which is the
    shape every page has until it is drawn. Placed as a tall page, at the top, a landscape page
    under half a screen high left the middle of the screen on the page after it.
    """
    viewer("pdf.html", turned_pdf, resume=5)
    wait_for_pdf(page)
    p = port()
    p.wait_for(r"page 5 12")
    middle = page.evaluate(
        "() => { const b = document.querySelectorAll('#pages > *')[4].getBoundingClientRect();"
        " const m = innerHeight / 2; return b.top <= m && b.bottom >= m; }"
    )
    assert middle, "the middle of the screen is not on the page reopened at"


def test_go_to_page_on_a_landscape_page_after_a_portrait_one_stays_on_it(viewer, page, port, turned_pdf):
    viewer("pdf.html", turned_pdf)
    wait_for_pdf(page)
    p = port()
    p.wait_for(r"page 1 12")
    p.go_to_page(5)
    p.wait_for(r"page 5 12")
    # Drawn by now, and so at its own shape
    page.wait_for_timeout(1500)
    nudge(page)
    assert settled_page(page, p) == "page 5 12"


def test_a_page_past_the_end_is_not_reopened_at(viewer, page, port, turned_pdf):
    viewer("pdf.html", turned_pdf, resume=40)
    wait_for_pdf(page)
    p = port()
    assert p.wait_for(r"page 1 12")
