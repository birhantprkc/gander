"""prose.html: the OpenDocument text and Rich Text readers, one behaviour at a time.

test_prose.py proves the page on three fixtures that carry one document. Each test here
is the smallest file that shows one thing a reader must do, written for that test:
Rich Text is text, so it is written out in the test, and an OpenDocument file is a zip,
so it is built in the test with zipfile. The expected results are LibreOffice's reading
of the same files, except where a docstring says otherwise.
"""

import io
import zipfile

from helpers import status_text


def drawn(page):
    """Waits for the reader to finish, with a document or with the card."""
    page.wait_for_function(
        "() => { const s = document.getElementById('vw-status');"
        "return !!s && (s.style.display === 'none' || s.classList.contains('vw-error')); }",
        timeout=15000,
    )


def open_rtf(viewer, page, made, text, name="t.rtf"):
    viewer("prose.html", made(name, text.encode("latin-1")))
    drawn(page)
    assert not page.query_selector(".vw-error"), status_text(page)


def paper_text(page):
    return page.evaluate("() => document.querySelector('#container').innerText")


def note_text(page):
    return page.evaluate("() => document.querySelector('.vw-notes').innerText")


# ------------------------------------------------------------------------------------
# Rich Text: footnotes
# ------------------------------------------------------------------------------------

def test_a_footnote_decodes_its_escaped_bytes(viewer, page, made):
    """
    A byte written as \\'hh was decoded only in the body, so in a footnote it was dropped:
    "caf\\'e9" read "caf". Being dropped, it also never used up the fallback a \\u
    character leaves behind it, so the character after the fallback went instead.
    """
    open_rtf(viewer, page, made,
             r"{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0\froman Times New Roman;}}"
             r"\pard Body{\super\chftn}{\footnote\pard{\super\chftn} Note caf\'e9 na\'efve,"
             r" \u233\'e9 x\par}\par}")
    notes = note_text(page)
    assert "Note café naïve" in notes
    assert "é x" in notes


def test_a_backslash_before_a_line_end_ends_a_paragraph_in_a_footnote(viewer, page, made):
    """A backslash and a line end is \\par, in a footnote as in the body."""
    open_rtf(viewer, page, made,
             "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\froman Times New Roman;}}"
             "\\pard Body\\chftn{\\footnote\\pard\\chftn First line\\\nSecond line\\par}\\par}")
    paragraphs = page.evaluate(
        "() => [...document.querySelectorAll('.vw-notes p')].map(p => p.textContent.trim())"
    )
    assert paragraphs == ["1 First line", "Second line"]
