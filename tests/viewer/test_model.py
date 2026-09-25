"""
model.html: a 3D model, drawn with WebGL.

Headless Chromium draws WebGL with SwiftShader on the CPU, on the Mac and in the CI
container alike, and draws the same scene the same way every time. So a picture here is
taken as a screenshot and measured rather than compared with a reference: how much of it is
the model, where the model's middle is, and whether two pictures are the same one.

The reader in model-stl.js is also driven directly, in the page, because what matters most
about it cannot be seen in a picture: that a file read in pieces of any size comes out the
same as one read whole, and which files it refuses.
"""

import io
import struct

import pytest
from PIL import Image, ImageChops

GROUND = (0x34, 0x30, 0x29)

DONE = (
    "() => ['drawn', 'failed'].includes("
    "document.getElementById('model').getAttribute('data-state'))"
)

# The bracket in tests/fixtures: an L 40 long, 20 deep and 30 tall, in twenty triangles,
# its corners off the origin so the viewer has to find its middle.
BRACKET_TRIANGLES = 20


def open_model(viewer, page, fixture, **params):
    viewer("model.html", fixture, **params)
    page.wait_for_function(DONE, timeout=20000)
    return page


def state(page):
    return page.get_attribute("#model", "data-state")


def triangles(page):
    return int(page.get_attribute("#model", "data-triangles"))


def card(page):
    return page.evaluate(
        "() => { const e = document.getElementById('vw-status');"
        "return e && e.style.display !== 'none' ? e.textContent : null; }"
    )


def picture(page):
    """What is on the screen once the frame asked for has been drawn."""
    page.evaluate("() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))")
    return Image.open(io.BytesIO(page.screenshot())).convert("RGB")


def model_in(img):
    """How many sampled pixels are the model rather than the ground, and their middle."""
    px = img.load()
    w, h = img.size
    n = sx = sy = 0
    # The line of text at the foot is left out: it is not the model
    for y in range(0, h - 80, 3):
        for x in range(0, w, 3):
            r, g, b = px[x, y]
            if abs(r - GROUND[0]) + abs(g - GROUND[1]) + abs(b - GROUND[2]) > 30:
                n += 1
                sx += x
                sy += y
    return n, ((sx / n, sy / n) if n else (0.0, 0.0))


def changed(a, b):
    """The share of pixels that differ between two pictures by more than a shade."""
    shades = ImageChops.difference(a, b).convert("L").histogram()
    return sum(shades[13:]) / (a.size[0] * a.size[1])


def binary_stl(tris, count=None, header=b"made by a test"):
    body = bytearray(header.ljust(80, b" "))
    body += struct.pack("<I", len(tris) if count is None else count)
    for tri in tris:
        body += struct.pack("<3f", 0, 0, 0)
        for corner in tri:
            body += struct.pack("<3f", *corner)
        body += b"\x00\x00"
    return bytes(body)


def sheet(cells):
    """
    A flat square 100 across, in cells by cells squares of two triangles each, as a binary
    STL. The way to make a large model that is also quick to draw: every triangle covers
    its own small patch of the screen, where the same triangle repeated would cover the
    same large one over and over, which the software renderer here takes minutes over.
    """
    step = 100.0 / cells
    record = struct.Struct("<12fH")
    body = bytearray(b"a sheet made by a test".ljust(80, b" "))
    body += struct.pack("<I", 2 * cells * cells)
    for i in range(cells):
        x0, x1 = i * step, (i + 1) * step
        for j in range(cells):
            y0, y1 = j * step, (j + 1) * step
            body += record.pack(0, 0, 1, x0, y0, 0, x1, y0, 0, x1, y1, 0, 0)
            body += record.pack(0, 0, 1, x0, y0, 0, x1, y1, 0, x0, y1, 0, 0)
    return bytes(body)


def bracket_records(fixture_path):
    data = fixture_path("bracket.stl").read_bytes()
    return data[:84], [data[84 + 50 * i:84 + 50 * (i + 1)] for i in range(BRACKET_TRIANGLES)]


# ---------------------------------------------------------------------------
# Drawing
# ---------------------------------------------------------------------------

def test_a_binary_stl_is_drawn(viewer, page):
    open_model(viewer, page, "bracket.stl")
    assert state(page) == "drawn"
    assert triangles(page) == BRACKET_TRIANGLES
    covered, _ = model_in(picture(page))
    # Neither a blank screen nor one flooded with colour
    assert 1500 < covered < 40000
    assert card(page) is None


def test_the_same_model_as_text_is_drawn_the_same(viewer, page):
    open_model(viewer, page, "bracket.stl")
    binary = picture(page)
    open_model(viewer, page, "bracket-ascii.stl")
    assert triangles(page) == BRACKET_TRIANGLES
    assert changed(binary, picture(page)) < 0.001


def test_the_size_is_given_in_millimetres(viewer, page):
    open_model(viewer, page, "bracket.stl")
    assert page.text_content("#size") == "40 × 20 × 30 mm"


def test_a_screen_reader_is_told_what_the_model_is(viewer, page):
    open_model(viewer, page, "bracket.stl")
    assert page.get_attribute("#model", "role") == "img"
    assert page.get_attribute("#model", "aria-label") == \
        "3D model, 40 by 20 by 30 millimetres, 20 triangles"
    # Said once: the line under the model is the same words for the eye
    assert page.get_attribute("#size", "aria-hidden") == "true"


def test_a_model_away_from_the_origin_is_still_in_the_middle(viewer, page):
    """The bracket's corners run from X 10 to 50, so a viewer that assumed the origin was
    the model's middle would draw it off to one side."""
    open_model(viewer, page, "bracket.stl")
    img = picture(page)
    _, (x, y) = model_in(img)
    w, h = img.size
    assert abs(x - w / 2) < 0.12 * w
    assert abs(y - h / 2) < 0.12 * h


def test_the_model_is_fitted_above_the_line_giving_its_size(viewer, page):
    """Found on the emulator: a phone turned on its side put the size line across the foot of
    the model. The line is hidden once the model is drawn, so what is left is only model."""
    page.set_viewport_size({"width": 980, "height": 420})
    open_model(viewer, page, "bracket.stl")
    line = page.evaluate("() => document.getElementById('size').getBoundingClientRect().top")
    page.evaluate("() => { document.getElementById('size').style.visibility = 'hidden'; }")
    img = picture(page)
    px = img.load()
    lowest = max(
        (y for y in range(img.size[1]) for x in range(0, img.size[0], 2)
         if sum(abs(a - b) for a, b in zip(px[x, y], GROUND)) > 30),
        default=None,
    )
    assert lowest is not None
    assert lowest < line


def test_faces_listed_the_wrong_way_round_are_lit_the_same(viewer, page, made, fixture_path):
    """
    STL files often list some faces' corners the wrong way round. The normal is worked out
    from the corners, so every one of those faces has it pointing inwards, and drawn as it
    is the face would come out black. Turning every face of the bracket inside out must
    leave the picture exactly as it was.
    """
    open_model(viewer, page, "bracket.stl")
    right_way = picture(page)

    header, records = bracket_records(fixture_path)
    flipped = bytearray(header)
    for r in records:
        flipped += r[:24] + r[36:48] + r[24:36] + r[48:]
    open_model(viewer, page, made("inside-out.stl", bytes(flipped)))
    assert changed(right_way, picture(page)) < 0.001


def test_a_face_with_no_area_is_not_drawn(viewer, page, made):
    flat = [(0, 0, 0), (10, 0, 0), (20, 0, 0)]
    real = [(0, 0, 0), (10, 0, 0), (0, 10, 0)]
    open_model(viewer, page, made("with-a-line.stl", binary_stl([flat, real])))
    assert state(page) == "drawn"
    assert triangles(page) == 1


def test_a_face_with_a_corner_out_of_range_is_not_drawn(viewer, page, made):
    """
    A NaN or an infinity in a corner would draw something wrong, and would make the model's
    size infinite too. So would a face 10^30 across: no model is that big, and one that
    size would leave the real faces beside it too small to see.
    """
    nan = float("nan")
    tris = [
        [(0, 0, 0), (nan, 0, 0), (0, 10, 0)],
        [(0, 0, 0), (float("inf"), 0, 0), (0, 10, 0)],
        [(0, 0, 0), (1e30, 0, 0), (0, 1e30, 0)],
        [(0, 0, 0), (10, 0, 0), (0, 10, 0)],
    ]
    open_model(viewer, page, made("with-a-nan.stl", binary_stl(tris)))
    assert triangles(page) == 1
    assert page.text_content("#size") == "10 × 10 × 0 mm"


# ---------------------------------------------------------------------------
# Binary files the header gets wrong
# ---------------------------------------------------------------------------

def test_a_binary_file_that_begins_with_solid_is_read_as_binary(viewer, page, fixture_path):
    """SolidWorks, among others, begins a binary file's header with the word a text STL
    begins with. The fixture is one of those."""
    assert fixture_path("bracket.stl").read_bytes()[:5] == b"solid"
    open_model(viewer, page, "bracket.stl")
    assert triangles(page) == BRACKET_TRIANGLES


def test_a_count_of_zero_means_read_to_the_end(viewer, page, made, fixture_path):
    header, records = bracket_records(fixture_path)
    data = header[:80] + struct.pack("<I", 0) + b"".join(records)
    open_model(viewer, page, made("uncounted.stl", data))
    assert triangles(page) == BRACKET_TRIANGLES


def test_a_file_cut_off_part_way_draws_what_arrived(viewer, page, made, fixture_path):
    data = fixture_path("bracket.stl").read_bytes()
    open_model(viewer, page, made("cut-off.stl", data[:84 + 50 * 12 + 25]))
    assert state(page) == "drawn"
    assert triangles(page) == 12


def test_what_comes_after_the_last_counted_face_is_not_read(viewer, page, made, fixture_path):
    data = fixture_path("bracket.stl").read_bytes()
    extra = binary_stl([[(500, 500, 500), (600, 500, 500), (500, 600, 500)]])[84:]
    open_model(viewer, page, made("trailing.stl", data + extra))
    assert triangles(page) == BRACKET_TRIANGLES
    assert page.text_content("#size") == "40 × 20 × 30 mm"


# ---------------------------------------------------------------------------
# The reader, driven directly
# ---------------------------------------------------------------------------

# Reads the open document whole, then again in pieces of every size from one byte up, and
# in pieces of random sizes, and answers how many triangles each read found and whether
# every read found the same ones in the same order.
READ_IN_PIECES = """async () => {
  const bytes = new Uint8Array(await (await fetch(vwDocUrl())).arrayBuffer());
  function read(sizes) {
    const out = [];
    const r = vwStlReader(bytes.length, () => {}, function () {
      out.push(Array.prototype.join.call(arguments, ','));
    });
    let at = 0, i = 0;
    while (at < bytes.length) {
      const n = sizes(i++);
      r.push(bytes.slice(at, at + n));
      at += n;
    }
    r.end();
    return out.join(';');
  }
  const whole = read(() => bytes.length);
  let seed = 7;
  const random = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return 1 + seed % 97; };
  const reads = [read(() => 1), read(() => 49), read(() => 50), read(() => 51), read(random)];
  return { triangles: whole ? whole.split(';').length : 0,
           same: reads.every(r => r === whole) };
}"""


@pytest.mark.parametrize("fixture", ["bracket.stl", "bracket-ascii.stl"])
def test_a_file_read_in_pieces_of_any_size_reads_the_same(viewer, page, fixture):
    open_model(viewer, page, fixture)
    result = page.evaluate(READ_IN_PIECES)
    assert result["triangles"] == BRACKET_TRIANGLES
    assert result["same"]


KIND = """([head, total]) => vwStlKind(new TextEncoder().encode(head), total)"""


def test_the_length_decides_a_binary_file_before_its_bytes_do(viewer, page):
    """
    A header and a count that happen to be printable, followed by more printable bytes,
    look like text. When the file's length is exactly what that count says, it is binary
    all the same. The count here is 0x20202020, four spaces.
    """
    open_model(viewer, page, "bracket.stl")
    head = "solid" + " " * 507
    assert page.evaluate(KIND, [head, 84 + 50 * 0x20202020]) == "binary"
    assert page.evaluate(KIND, [head, -1]) == "ascii"
    assert page.evaluate(KIND, [head, 10000]) == "ascii"


def test_text_that_does_not_begin_with_solid_is_not_read_as_binary(viewer, page):
    open_model(viewer, page, "bracket.stl")
    assert page.evaluate(KIND, ["These are notes about a model rather than a model. " * 3, 153]) \
        is None


READ_TEXT = """(text) => {
  const found = [];
  const r = vwStlReader(-1, () => {}, function () { found.push(Array.from(arguments)); });
  r.push(new TextEncoder().encode(text));
  r.end();
  return found;
}"""


def facet(a, b, c, vertex="vertex", facet_word="facet", loop="outer loop"):
    corners = "".join(f"  {vertex} {x} {y} {z}\n" for x, y, z in (a, b, c))
    return f"{facet_word} normal 0 0 1\n {loop}\n{corners} endloop\nendfacet\n"


def test_a_text_file_in_capitals_is_read(viewer, page):
    open_model(viewer, page, "bracket.stl")
    text = "SOLID SHOUTING\n" + facet(
        (0, 0, 0), (1, 0, 0), (0, 1, 0), vertex="VERTEX", facet_word="FACET", loop="OUTER LOOP"
    ).replace("endloop", "ENDLOOP").replace("endfacet", "ENDFACET") + "ENDSOLID SHOUTING\n"
    assert page.evaluate(READ_TEXT, text) == [[0, 0, 0, 1, 0, 0, 0, 1, 0]]


def test_several_solids_in_one_file_are_all_read(viewer, page):
    open_model(viewer, page, "bracket.stl")
    text = (
        "solid one\n" + facet((0, 0, 0), (1, 0, 0), (0, 1, 0)) + "endsolid one\n" +
        "solid two\n" + facet((5, 5, 5), (6, 5, 5), (5, 6, 5)) + "endsolid two\n"
    )
    assert len(page.evaluate(READ_TEXT, text)) == 2


def test_a_facet_with_a_bad_number_costs_that_facet_only(viewer, page):
    open_model(viewer, page, "bracket.stl")
    text = (
        "solid x\n" +
        facet((0, 0, 0), (1, 0, 0), (0, 1, 0)) +
        facet((0, 0, 0), ("nan", 0, 0), (0, 1, 0)) +
        facet((0, 0, 0), (2, 0, 0), (0, 2, 0)) +
        "endsolid x\n"
    )
    assert page.evaluate(READ_TEXT, text) == [
        [0, 0, 0, 1, 0, 0, 0, 1, 0],
        [0, 0, 0, 2, 0, 0, 0, 2, 0],
    ]


def test_a_facet_short_of_a_corner_costs_that_facet_only(viewer, page):
    open_model(viewer, page, "bracket.stl")
    short = facet((0, 0, 0), (1, 0, 0), (0, 1, 0)).replace("  vertex 0 1 0\n", "")
    text = "solid x\n" + short + facet((0, 0, 0), (3, 0, 0), (0, 3, 0)) + "endsolid x\n"
    assert page.evaluate(READ_TEXT, text) == [[0, 0, 0, 3, 0, 0, 0, 3, 0]]


def test_a_name_is_not_taken_for_numbers(viewer, page):
    open_model(viewer, page, "bracket.stl")
    text = "solid 12 34 56\n" + facet((0, 0, 0), (1, 0, 0), (0, 1, 0)) + "endsolid 12 34 56\n"
    assert page.evaluate(READ_TEXT, text) == [[0, 0, 0, 1, 0, 0, 0, 1, 0]]


NUMBER = """(words) => words.map(w => {
  const v = vwStlNumber(new TextEncoder().encode(w), 0, w.length);
  return v !== v ? 'NaN' : (Object.is(v, -0) ? '-0' : v);
})"""

PARSE_FLOAT = """(words) => words.map(w => {
  const v = parseFloat(w);
  return Object.is(v, -0) ? '-0' : v;
})"""


def test_numbers_come_out_as_parse_float_reads_them(viewer, page):
    """Exactly, for every number an exporter writes: fifteen significant digits at most,
    and an exponent within twenty-two."""
    open_model(viewer, page, "bracket.stl")
    words = [
        "0", "-0", "1", "+1", "-1.5", "1.", ".5", "-.5", "00012.5000",
        "1e3", "1E3", "1e+3", "1e-3", "-2.5E-7", "6.02214076e23",
        "1.000000e+01", "-1.000000e+001", "1.234567e-005",
        "0.1", "0.2", "0.3", "123456.789012345", "3.14159265358979",
        "1e22", "1e-22",
    ]
    assert page.evaluate(NUMBER, words) == page.evaluate(PARSE_FLOAT, words)


def test_numbers_past_that_come_out_as_the_same_float32(viewer, page):
    """Every corner is stored as a float32, so this is all the rest have to agree on."""
    open_model(viewer, page, "bracket.stl")
    words = ["3.14159265358979323846264", "123456789012345678901234", "3.402823e+38",
             "1.175494e-38", "4.9406564584124654e-300", "1e-400", "1e400"]
    got = page.evaluate(
        "(words) => words.map(w => Math.fround(vwStlNumber(new TextEncoder().encode(w), 0, w.length)))",
        words,
    )
    want = page.evaluate("(words) => words.map(w => Math.fround(parseFloat(w)))", words)
    assert got == want


def test_what_is_not_a_number_is_said_to_be_so(viewer, page):
    open_model(viewer, page, "bracket.stl")
    words = ["", "-", "+", ".", "e5", "1e", "1e+", "--1", "1.2.3", "nan", "inf",
             "0x10", "1,5", "12a", "1e5x"]
    assert page.evaluate(NUMBER, words) == ["NaN"] * len(words)


# ---------------------------------------------------------------------------
# What is refused, and how
# ---------------------------------------------------------------------------

def error(page):
    page.wait_for_selector(".vw-error", timeout=10000)
    return page.text_content(".vw-error-title"), page.text_content(".vw-error-detail")


def test_text_that_is_not_an_stl_says_so(viewer, page, made):
    open_model(viewer, page, made("notes.stl", "These are notes about a model. " * 6))
    title, detail = error(page)
    assert state(page) == "failed"
    assert title == "Could not show this model"
    assert "begin the way an STL file does" in detail


def test_an_empty_file_says_so(viewer, page, made):
    open_model(viewer, page, made("nothing.stl", b""))
    assert error(page) == ("Could not show this model", "The file is empty.")


def test_a_model_with_no_triangles_says_so(viewer, page, made):
    open_model(viewer, page, made("hollow.stl", "solid hollow\nendsolid hollow\n"))
    title, _ = error(page)
    assert title == "This model is empty"


def test_a_file_that_cannot_be_read_says_so(viewer, page):
    open_model(viewer, page, "bracket.stl", status=404)
    title, detail = error(page)
    assert title == "Could not show this model"
    assert "HTTP 404" in detail


# A phone that says it has almost no memory, so the line falls at ten triangles.
TINY_PHONE = """Object.defineProperty(Navigator.prototype, 'deviceMemory',
  { configurable: true, get: () => 0.00002 });"""


def test_a_binary_model_too_large_for_the_phone_is_refused_by_its_count(viewer, page):
    page.add_init_script(TINY_PHONE)
    open_model(viewer, page, "bracket.stl")
    title, detail = error(page)
    assert title == "This model is too large to show"
    assert detail == ("Gander draws up to 10 triangles on a phone with this much memory, "
                      "and this model has 20.")


def test_a_text_model_too_large_for_the_phone_is_refused_as_it_is_read(viewer, page):
    """A text file's count is not known until it has all been read, so the line is drawn
    at the triangle that crosses it."""
    page.add_init_script(TINY_PHONE)
    open_model(viewer, page, "bracket-ascii.stl")
    title, detail = error(page)
    assert title == "This model is too large to show"
    assert detail.endswith("and this model has more.")


def test_without_webgl_it_says_why(viewer, page):
    page.add_init_script(
        "HTMLCanvasElement.prototype.getContext = function () { return null; };"
    )
    open_model(viewer, page, "bracket.stl")
    title, detail = error(page)
    assert title == "3D models cannot be shown on this phone"
    assert "WebGL" in detail


def test_the_model_counts_into_the_millions_in_words(viewer, page):
    open_model(viewer, page, "bracket.stl")
    counts = page.evaluate("() => [10, 125000, 999999, 1000000, 2460000].map(vwModelCount)")
    assert counts == ["10", "125,000", "999,999", "1 million", "2.5 million"]


def test_a_large_file_says_how_far_it_has_got(viewer, page, made):
    """Past a megabyte the card goes up at once rather than after the usual wait, and
    counts up as the file arrives. 24,200 triangles is 1.2 MB."""
    open_model(viewer, page, made("big.stl", sheet(110)))
    assert state(page) == "drawn"
    assert triangles(page) == 24200
    classes = page.get_attribute("#vw-status", "class")
    assert "vw-wait" not in classes
    assert page.text_content("#vw-status") == "Opening model… 100%"


def served_claiming(page, data, length):
    """Answers the document with [data], saying it is [length] long as the WebView would."""
    page.route("**/doc/**", lambda route: route.fulfill(status=200, body=data, headers={
        "Content-Type": "application/vnd.ms-pki.stl",
        "Content-Length": length,
    }))


def test_a_model_out_of_a_zip_is_read_whatever_length_the_webview_claims(viewer, page, made):
    """
    Found on the emulator, opening a model inside a zip, and it opened as an empty one. The
    WebView gives the page a Content-Length of its own, made from what the stream has to
    hand when it starts, and a file coming out of a zip through a pipe has nothing to hand:
    it said 0, with the whole model behind it. The page goes by the length on its URL, which
    for such a file is not there at all, since its provider could not say either.
    """
    data = sheet(110)
    served_claiming(page, data, "0")
    open_model(viewer, page, made("zipped.stl", data), length=None)
    assert state(page) == "drawn"
    assert triangles(page) == 24200
    # With no length to go by, the card has no percentage to give
    assert page.text_content("#vw-status") == "Opening model…"


def test_the_card_counts_by_the_length_on_the_url(viewer, page, made):
    """For a file on the phone the WebView doubles the length instead, "N, N", which is not
    a number at all. The percentage comes from the URL, so neither matters."""
    data = sheet(110)
    served_claiming(page, data, f"{len(data)}, {len(data)}")
    open_model(viewer, page, made("doubled.stl", data))
    assert page.url.count(f"length={len(data)}") == 1
    assert page.text_content("#vw-status") == "Opening model… 100%"


# Whether any frame was drawn with the card on the screen before the model was, which is
# the only way the reader could have seen it. Showing in its style is not enough: a card
# made visible and hidden again inside one task is never drawn.
CARD_IN_A_FRAME = """
window.cardFramed = false;
(function frame() {
  var e = document.getElementById('vw-status');
  var s = e && getComputedStyle(e);
  var m = document.getElementById('model');
  if (m && m.getAttribute('data-state') === 'drawn') return;
  if (s && s.display !== 'none' && s.visibility === 'visible') window.cardFramed = true;
  requestAnimationFrame(frame);
})();
"""


def test_a_model_that_takes_a_while_to_read_shows_the_card_while_it_is_read(viewer, page, made):
    """The file is read on the page's own thread, and the reading steps aside every tenth of
    a second so a frame can show how far it has got. Four hundred thousand triangles, 20 MB,
    takes well over that on any machine."""
    page.add_init_script(CARD_IN_A_FRAME)
    open_model(viewer, page, made("slow.stl", sheet(448)))
    assert state(page) == "drawn"
    assert page.evaluate("() => window.cardFramed") is True


def test_a_model_read_in_a_moment_never_shows_the_card(viewer, page):
    page.add_init_script(CARD_IN_A_FRAME)
    open_model(viewer, page, "bracket.stl")
    assert page.evaluate("() => window.cardFramed") is False


# ---------------------------------------------------------------------------
# Moving it
# ---------------------------------------------------------------------------

def test_dragging_turns_the_model_and_a_double_tap_puts_it_back(viewer, page):
    open_model(viewer, page, "bracket.stl")
    start = picture(page)

    page.mouse.move(490, 800)
    page.mouse.down()
    page.mouse.move(590, 820, steps=8)
    page.mouse.up()
    turned = picture(page)
    assert changed(start, turned) > 0.01

    page.mouse.dblclick(490, 400)
    assert changed(start, picture(page)) == 0


def test_turning_before_the_model_arrives_does_nothing(viewer, page):
    """Until the model is there, there is no screen size or lens to measure a move against,
    and a pinch on the empty page would leave the model to open somewhere off to one side."""
    held = []
    page.route("**/doc/**", lambda route: held.append(route))
    viewer("model.html", "bracket.stl")
    page.wait_for_function("() => typeof vwModelView === 'object'")
    page.mouse.move(490, 800)
    page.mouse.wheel(0, -300)
    page.mouse.down()
    page.mouse.move(600, 820, steps=5)
    page.mouse.up()
    for _ in range(100):
        if held:
            break
        page.wait_for_timeout(50)
    for route in held:
        route.continue_()
    page.wait_for_function(DONE, timeout=20000)
    assert page.evaluate(
        "() => vwModelView.zoom === 1 && vwModelView.yaw === VW_MODEL_YAW &&"
        " vwModelView.pitch === VW_MODEL_PITCH && vwModelView.target.every(v => v === 0)"
    )


def test_the_wheel_zooms_about_the_pointer(viewer, page):
    """
    The point under the pointer stays put while the rest grows away from it, so the
    model's middle moves away from where the pointer is, not from the middle of the
    screen. Zoomed a little, so that the whole model is still on the screen to be
    measured, and far enough from the middle that the two answers are 40 to 50 pixels
    apart. Points nearer or further than the one the camera looks at drift a little
    either way, which is what the tolerance is for.
    """
    open_model(viewer, page, "bracket.stl")
    covered, (x, y) = model_in(picture(page))
    at = (x - 150, y + 100)
    page.mouse.move(*at)
    page.mouse.wheel(0, -100)
    grown, (x2, y2) = model_in(picture(page))
    factor = page.evaluate("() => vwModelView.zoom")
    assert 1.2 < factor < 1.25
    assert grown > covered * 1.35
    # Where the middle would be if the picture had grown about the pointer
    want = (at[0] + (x - at[0]) * factor, at[1] + (y - at[1]) * factor)
    assert abs(x2 - want[0]) < 15 and abs(y2 - want[1]) < 15


def test_moving_it_follows_the_pointer(viewer, page):
    open_model(viewer, page, "bracket.stl")
    _, (x, y) = model_in(picture(page))
    page.mouse.move(490, 800)
    page.mouse.down(button="right")
    page.mouse.move(570, 740, steps=8)
    page.mouse.up(button="right")
    _, (x2, y2) = model_in(picture(page))
    assert abs((x2 - x) - 80) < 8
    assert abs((y2 - y) + 60) < 8


def touch(cdp, kind, points):
    cdp.send("Input.dispatchTouchEvent", {
        "type": kind,
        "touchPoints": [{"x": x, "y": y, "id": i} for i, (x, y) in enumerate(points)],
    })


def test_two_fingers_pinch_to_zoom(viewer, page):
    open_model(viewer, page, "bracket.stl")
    covered, _ = model_in(picture(page))
    cdp = page.context.new_cdp_session(page)
    touch(cdp, "touchStart", [(460, 800), (520, 800)])
    for k in range(1, 7):
        touch(cdp, "touchMove", [(460 - 5 * k, 800), (520 + 5 * k, 800)])
    touch(cdp, "touchEnd", [])
    # Sixty pixels apart to a hundred and twenty. Twice the size runs off the screen, so
    # the picture only has to show that it grew.
    assert page.evaluate("() => vwModelView.zoom") == pytest.approx(2, abs=0.01)
    grown, _ = model_in(picture(page))
    assert grown > covered * 1.5


def test_one_finger_turns_it(viewer, page):
    open_model(viewer, page, "bracket.stl")
    start = picture(page)
    cdp = page.context.new_cdp_session(page)
    touch(cdp, "touchStart", [(490, 800)])
    for k in range(1, 9):
        touch(cdp, "touchMove", [(490 + 12 * k, 800)])
    touch(cdp, "touchEnd", [])
    assert changed(start, picture(page)) > 0.01


# ---------------------------------------------------------------------------
# The graphics context
# ---------------------------------------------------------------------------

def test_a_lost_context_reads_the_model_again(viewer, page, server):
    """
    The triangles live only on the graphics card, so when Android takes the context back
    the page has nothing left to draw from, and reads the file again once it is returned.
    The view the reader had is kept.
    """
    open_model(viewer, page, "bracket.stl")
    page.mouse.move(490, 800)
    page.mouse.down()
    page.mouse.move(560, 790, steps=6)
    page.mouse.up()
    before = picture(page)
    reads = len(server.full_requests())

    page.evaluate("() => { window.__lose = document.getElementById('model')"
                  ".getContext('webgl').getExtension('WEBGL_lose_context');"
                  "__lose.loseContext(); }")
    page.wait_for_function("() => document.getElementById('model')"
                           ".getAttribute('data-state') === 'lost'")
    page.evaluate("() => __lose.restoreContext()")
    page.wait_for_function("() => document.getElementById('model')"
                           ".getAttribute('data-state') === 'drawn'", timeout=20000)

    assert len(server.full_requests()) == reads + 1
    assert changed(before, picture(page)) < 0.001
