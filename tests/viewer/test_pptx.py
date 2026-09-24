"""pptx.html: PPTXjs, which reports nothing and is polled instead."""

import pytest

from helpers import wait_until_done


def test_a_deck_renders_every_slide(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    assert len(page.query_selector_all("#result .slide")) >= 3


def test_the_spinner_goes_once_the_slides_are_up(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'; }",
        timeout=20000,
    )


def test_the_slide_titles_are_there(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    # PPTXjs lays every run out with non-breaking spaces between the words
    said = page.text_content("#result").replace("\u00a0", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said


# ---------------------------------------------------------------------------
# PowerPoint's relatives, which FileKind sends here by extension
# ---------------------------------------------------------------------------

SLIDE_RELATIVES = {
    "deck.ppsx": "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
    "deck.pptm": "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
    "deck.potx": "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
}


@pytest.mark.parametrize("fixture", sorted(SLIDE_RELATIVES))
def test_a_slide_show_a_macro_enabled_deck_and_a_template_render_as_a_pptx_does(
    viewer, page, main_part, fixture
):
    """
    Each is deck.pptx with its main part declared as its own format's. PPTXjs
    opens ppt/presentation.xml by name and finds the slides by their own type,
    which the four formats share, so what the main part was declared as never
    reaches it.
    """
    assert main_part(fixture) == [SLIDE_RELATIVES[fixture]]
    viewer("pptx.html", fixture)
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    wait_until_done(page)
    said = page.text_content("#result").replace(" ", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said
