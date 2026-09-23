"""
Reference screenshots, for the two pages whose look a structural check cannot
see.

What a golden is for here is what a page looks like: grounds, spacing, the
size and position of the things on it. A screenshot cannot see the text layer,
which is transparent by design, so that contract is asserted structurally in
test_pdf_render.py instead. The two layers were checked against each other by
breaking each contract in turn and confirming the other tier stayed green.

Three images, not thirty: these are reviewed in pull requests and regenerated
by hand, and a set that changes every time the CSS moves stops being read.

Linux only. Text rasterises differently on macOS, so a golden made on one and
compared on the other fails on every pixel and says nothing. Locally, run them
through the same container CI uses:

    scripts/test-viewer-visual.sh

Regenerate after a deliberate change:

    pytest tests/viewer/test_visual.py --update-goldens
"""

import os
import platform

import pytest
from PIL import Image, ImageChops

from helpers import wait_for_pdf, wait_for_text_layer

GOLDENS = __import__("pathlib").Path(__file__).parent / "goldens"

# A pixel may differ by this much per channel before it counts as changed:
# enough to absorb antialiasing noise, not enough to hide a moved element.
CHANNEL_TOLERANCE = 24

# And this fraction of the image may differ before the test fails.
AREA_TOLERANCE = 0.002

pytestmark = pytest.mark.visual


def compare(page, name, update):
    target = GOLDENS / f"{name}.png"
    shot = page.screenshot(full_page=False)

    if update or not target.exists():
        # In CI a missing golden was deleted or never committed, and writing one there
        # would pass whatever the page looks like now. GitHub Actions sets CI.
        if not update and os.environ.get("CI"):
            failed = GOLDENS / f"{name}.failed.png"
            failed.write_bytes(shot)
            pytest.fail(
                f"{name}: there is no golden to compare against. The render is beside "
                f"the goldens as {failed.name}; make the golden with --update-goldens "
                f"and commit it."
            )
        target.write_bytes(shot)
        if not update:
            pytest.skip(f"wrote a new golden for {name}; check it and commit it")
        return

    import io
    fresh = Image.open(io.BytesIO(shot)).convert("RGB")
    known = Image.open(target).convert("RGB")

    assert fresh.size == known.size, (
        f"{name}: {fresh.size} against a golden of {known.size}"
    )

    # The worst of the three channels per pixel, thresholded and counted.
    # Taken channel by channel rather than through a greyscale conversion,
    # which weights the channels and would dampen a change of hue alone.
    diff = ImageChops.difference(fresh, known)
    red, green, blue = diff.split()
    worst = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    changed = worst.point(lambda v: 255 if v > CHANNEL_TOLERANCE else 0).histogram()[255]
    fraction = changed / (fresh.width * fresh.height)

    if fraction > AREA_TOLERANCE:
        failed = GOLDENS / f"{name}.failed.png"
        failed.write_bytes(shot)
        diff.save(GOLDENS / f"{name}.diff.png")
        pytest.fail(
            f"{name}: {fraction:.2%} of pixels moved, over the {AREA_TOLERANCE:.2%} "
            f"allowed. The new render is beside the golden as {failed.name}."
        )


@pytest.fixture(autouse=True)
def linux_only():
    if platform.system() != "Linux":
        pytest.skip("goldens are made on Linux; text rasterises differently elsewhere")


@pytest.fixture
def update(request):
    return request.config.getoption("--update-goldens")


def test_a_pdf_page_looks_right(viewer, page, update):
    """
    One image rather than two. pdf.html keeps the same warm ground in either
    scheme, so a light and a dark golden of it would be the same file; that
    the ground does not move is asserted in test_theme.py instead.

    What this image holds is the rendered page on its surround: the paper's
    size and position, the margin around it, and the colour behind it.
    """
    page.emulate_media(color_scheme="dark")
    viewer("pdf.html", "six-pages.pdf")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    page.wait_for_timeout(600)
    compare(page, "pdf", update)


@pytest.mark.parametrize("scheme", ["light", "dark"])
def test_the_text_viewer_looks_right(viewer, page, scheme, update):
    page.emulate_media(color_scheme=scheme)
    viewer("text.html", "plain.txt")
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.length > 0", timeout=15000
    )
    page.wait_for_timeout(400)
    compare(page, f"text-{scheme}", update)
