"""
The Content Security Policy every viewer page runs under (ViewerPolicy.kt), tested by
trying the things it is there to stop. The other half, that nothing a renderer needs is
refused, is the check every test runs through conftest.py.
"""

import io
import zipfile

import pytest


def refused(page):
    return page.evaluate("() => window.__vwRefused")


@pytest.mark.refusals_expected
def test_script_the_page_did_not_ship_does_not_run(viewer, page):
    """
    The four ways script in a document gets into a page: a script element, an
    event-handler attribute, a string handed to a timer, and a javascript: link.
    Each would set the flag if it ran.
    """
    viewer("text.html", "plain.txt")
    page.wait_for_function("() => typeof vwFormatSize === 'function'", timeout=15000)
    page.evaluate("""() => {
        const ran = "window.__vwRan = (window.__vwRan || []).concat";

        const script = document.createElement("script");
        script.textContent = ran + "('script')";
        document.body.appendChild(script);

        const img = document.createElement("img");
        img.setAttribute("onerror", ran + "('handler')");
        img.src = "data:,not-an-image";
        document.body.appendChild(img);

        setTimeout(ran + "('timer')", 0);

        const link = document.createElement("a");
        link.href = "javascript:" + ran + "('link')";
        document.body.appendChild(link);
        link.click();
    }""")
    page.wait_for_function("() => window.__vwRefused.length >= 4", timeout=5000)
    page.wait_for_timeout(300)

    assert page.evaluate("() => window.__vwRan") is None
    directives = [r.split(" ")[0] for r in refused(page)]
    assert len(directives) == 4, refused(page)
    assert all(d.startswith("script-src") for d in directives), refused(page)


@pytest.mark.refusals_expected
def test_a_markdown_image_on_another_host_is_never_fetched(viewer, page, made):
    """
    DOMPurify's default profile keeps a remote image, which is a request a document
    could use to say it had been opened. The policy stops it before it is made.
    """
    markdown = made(
        "remote.md",
        "# Remote\n\n![pixel](https://example.com/pixel.png)\n\n"
        '<img src="http://example.com/raw.png">\n',
    )
    # A route is only reached by a request that got past the page, so anything it
    # records was really sent. It answers locally, so no test ever touches the network.
    # (The request event is no use here: like DevTools, it lists refused requests too.)
    asked = []

    def answer(route):
        asked.append(route.request.url)
        route.fulfill(status=204, body=b"")

    page.route("**://example.com/**", answer)
    viewer("md.html", markdown)
    page.wait_for_function("() => document.querySelector('#content').children.length > 0")
    page.wait_for_function("() => window.__vwRefused.length >= 2", timeout=5000)

    assert asked == []
    blocked = sorted(r for r in refused(page) if r.startswith("img-src"))
    assert len(blocked) == 2, refused(page)
    assert blocked[0].startswith("img-src http://example.com")
    assert blocked[1].startswith("img-src https://example.com")


REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def crafted_docx():
    """
    Just enough of a Word file for docx-preview: an embedded HTML part carrying a
    script, a link whose target is script, and an ordinary web link.
    """
    w = ('xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
         f'xmlns:r="{REL}"')
    rels = "http://schemas.openxmlformats.org/package/2006/relationships"
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr(
            "[Content_Types].xml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Default Extension="html" ContentType="text/html"/>'
            '<Override PartName="/word/document.xml" ContentType='
            '"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            "</Types>",
        )
        z.writestr(
            "_rels/.rels",
            f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="{rels}">'
            f'<Relationship Id="rId1" Type="{REL}/officeDocument" Target="word/document.xml"/>'
            "</Relationships>",
        )
        z.writestr(
            "word/document.xml",
            f'<?xml version="1.0" encoding="UTF-8"?><w:document {w}><w:body>'
            "<w:p><w:r><w:t>Before the part</w:t></w:r></w:p>"
            '<w:altChunk r:id="rIdPart"/>'
            '<w:p><w:hyperlink r:id="rIdScript"><w:r><w:t>Script link</w:t></w:r></w:hyperlink></w:p>'
            '<w:p><w:hyperlink r:id="rIdWeb"><w:r><w:t>Web link</w:t></w:r></w:hyperlink></w:p>'
            "</w:body></w:document>",
        )
        z.writestr(
            "word/_rels/document.xml.rels",
            f'<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="{rels}">'
            f'<Relationship Id="rIdPart" Type="{REL}/aFChunk" Target="part.html"/>'
            f'<Relationship Id="rIdScript" Type="{REL}/hyperlink" '
            'Target="javascript:window.__vwRan=1" TargetMode="External"/>'
            f'<Relationship Id="rIdWeb" Type="{REL}/hyperlink" '
            'Target="https://example.com/" TargetMode="External"/>'
            "</Relationships>",
        )
        z.writestr(
            "word/part.html",
            "<html><body><p>Embedded part</p>"
            "<script>window.__vwRan = 1; try { parent.__vwRan = 1; } catch (e) {}</script>"
            "</body></html>",
        )
    return out.getvalue()


def test_a_word_files_embedded_html_is_drawn_in_a_frame_that_can_run_nothing(viewer, page, made):
    """
    docx-preview draws an embedded HTML part in a srcdoc frame of its own and would
    leave it unsandboxed, the page's own origin, so its script would run as the page.
    """
    viewer("docx.html", made("crafted.docx", crafted_docx()))
    page.wait_for_function(
        "() => { const f = document.querySelector('#container iframe');"
        " return !!f && f.srcdoc.indexOf('Embedded part') >= 0; }",
        timeout=25000,
    )
    page.wait_for_timeout(500)

    assert page.eval_on_selector("#container iframe", "f => f.getAttribute('sandbox')") == ""
    assert page.evaluate("() => window.__vwRan") is None


def test_a_word_files_script_link_is_left_as_text(viewer, page, made):
    """The web link stays a link; the one whose target is script keeps its words only."""
    viewer("docx.html", made("crafted.docx", crafted_docx()))
    page.wait_for_function(
        "() => (document.querySelector('#container') || {}).textContent.indexOf('Web link') >= 0",
        timeout=25000,
    )
    page.wait_for_function("() => document.getElementById('vw-status').offsetParent === null")

    links = page.eval_on_selector_all(
        "#container a", "as => as.map(a => [a.textContent.trim(), a.getAttribute('href')])"
    )
    assert ["Script link", None] in links, links
    assert ["Web link", "https://example.com/"] in links, links


def test_only_web_mail_phone_and_in_page_links_keep_their_address(viewer, page):
    viewer("text.html", "plain.txt")
    page.wait_for_function("() => typeof vwDisarmLinks === 'function'", timeout=15000)
    kept = page.evaluate("""() => {
        const hrefs = [
            "#heading", "https://example.com/", "HTTP://EXAMPLE.COM/", "mailto:a@example.com",
            "tel:+15550100", "javascript:x()", " JavaScript:x()", "java\\nscript:x()",
            "vbscript:x", "data:text/html,x", "file:///etc/hosts", "intent://x#Intent;end",
            "text.html?ext=html", "/doc/file.pdf", "",
        ];
        const root = document.createElement("div");
        for (const h of hrefs) {
            const a = document.createElement("a");
            a.setAttribute("href", h);
            root.appendChild(a);
        }
        vwDisarmLinks(root);
        return Array.from(root.children).map(a => a.getAttribute("href")).filter(h => h !== null);
    }""")
    assert kept == [
        "#heading", "https://example.com/", "HTTP://EXAMPLE.COM/", "mailto:a@example.com",
        "tel:+15550100",
    ]
