"""prose.html: the OpenDocument text and Rich Text readers, one behaviour at a time.

test_prose.py proves the page on three fixtures that carry one document. Each test here
is the smallest file that shows one thing a reader must do, written for that test:
Rich Text is text, so it is written out in the test, and an OpenDocument file is a zip,
so it is built in the test with zipfile. The expected results are LibreOffice's reading
of the same files, except where a docstring says otherwise.
"""

import io
import zipfile

import pytest

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


# ------------------------------------------------------------------------------------
# Rich Text: sections
# ------------------------------------------------------------------------------------

RTF_HEAD = r"{\rtf1\ansi\deff0{\fonttbl{\f0\froman Times New Roman;}}"


def sheet_words(page):
    """The words on each sheet, sheet by sheet."""
    return page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section')]"
        ".map(s => s.querySelector('.vw-body').innerText.split(/\\s+/).filter(Boolean))"
    )


@pytest.mark.parametrize("sections, sheets", [
    (r"\sectd\sbknone\pard One\par\sect\sectd\pard Two\par", [["One"], ["Two"]]),
    (r"\sectd\pard One\par\sect\sectd\sbknone\pard Two\par", [["One", "Two"]]),
], ids=["first-runs-on", "second-runs-on"])
def test_a_section_break_is_the_kind_the_new_section_asks_for(viewer, page, made, sections, sheets):
    """
    Word and LibreOffice write a section's \\sbk after the \\sect that ends the one before
    it, so the break between two sections is the second one's. The first one's was used:
    a continuous first section ran the second on, and a continuous second one did not.
    """
    open_rtf(viewer, page, made, RTF_HEAD + sections + "}")
    assert sheet_words(page) == sheets


def test_a_header_set_after_a_section_break_heads_the_sheet_that_section_starts(viewer, page, made):
    """The sheet was made at \\sect, before the new section's header had been read."""
    open_rtf(viewer, page, made,
             RTF_HEAD + r"\sectd{\header\pard First head\par}\pard One\par"
             r"\sect\sectd{\header\pard Second head\par}\pard Two\par}")
    headers = page.evaluate(
        "() => [...document.querySelectorAll('.vw-paper > section .vw-header')]"
        ".map(h => h.textContent.trim())"
    )
    assert headers == ["First head", "Second head"]


def test_a_section_break_at_the_very_end_leaves_no_empty_sheet(viewer, page, made):
    open_rtf(viewer, page, made, RTF_HEAD + r"\sectd\pard One\par\sect}")
    assert sheet_words(page) == [["One"]]
