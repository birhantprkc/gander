"""
The master document the evaluation corpus is converted from.

One .docx carrying every feature a renderer should be judged on, written with
python-docx so it can be regenerated. LibreOffice, Cocoa's textutil and pandoc
each then write it out as .odt, .rtf and .doc, which gives three writers'
dialects of each format from the same content.

Everything in it is invented, in the Willowmere world the repo's fixtures use.
"""

import io
import sys
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_COLOR_INDEX
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from PIL import Image, ImageDraw

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")


def chart_png():
    """A bar chart drawn by hand, so the image has hard edges that show scaling."""
    im = Image.new("RGB", (640, 360), "#ffffff")
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 639, 359], outline="#22303c", width=3)
    bars = [(60, 210, "#2a6f97"), (170, 150, "#468faf"), (280, 250, "#61a5c2"),
            (390, 90, "#e07a5f"), (500, 180, "#81b29a")]
    for x, top, colour in bars:
        d.rectangle([x, top, x + 80, 320], fill=colour)
    d.line([40, 320, 610, 320], fill="#22303c", width=3)
    d.line([40, 40, 40, 320], fill="#22303c", width=3)
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def photo_jpeg():
    """A gradient with a disc on it: smooth tones, which is what JPEG is for."""
    im = Image.new("RGB", (480, 320))
    im.putdata([(40 + x // 3, 70 + y // 3, 140 + (x + y) // 8) for y in range(320) for x in range(480)])
    d = ImageDraw.Draw(im)
    d.ellipse([300, 40, 420, 160], fill="#f4e8c1")
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=82)
    return buf.getvalue()


def add_hyperlink(paragraph, text, url):
    part = paragraph.part
    rid = part.relate_to(
        url,
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
        is_external=True,
    )
    link = OxmlElement("w:hyperlink")
    link.set(qn("r:id"), rid)
    run = OxmlElement("w:r")
    props = OxmlElement("w:rPr")
    colour = OxmlElement("w:color")
    colour.set(qn("w:val"), "0563C1")
    under = OxmlElement("w:u")
    under.set(qn("w:val"), "single")
    props.append(colour)
    props.append(under)
    run.append(props)
    t = OxmlElement("w:t")
    t.text = text
    run.append(t)
    link.append(run)
    paragraph._p.append(link)


def shade(cell, fill):
    props = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    props.append(shd)


def build():
    doc = Document()
    section = doc.sections[0]
    section.page_width, section.page_height = Inches(8.27), Inches(11.69)   # A4
    section.left_margin = section.right_margin = Inches(1)
    section.header.paragraphs[0].text = "Willowmere Parish Council\tCondition survey, phase 3"
    section.footer.paragraphs[0].text = "HEADER-FOOTER-MARK Prepared for the clerk. Not for publication."

    doc.add_heading("Field Survey, Willowmere", level=0)
    doc.add_heading("1. Summary", level=1)

    p = doc.add_paragraph("The survey covered ")
    p.add_run("forty-two").bold = True
    p.add_run(" structures over ")
    p.add_run("eleven days").italic = True
    p.add_run(". Findings marked ")
    r = p.add_run("urgent")
    r.underline = True
    p.add_run(" need attention before winter, and one earlier finding has been ")
    r = p.add_run("withdrawn")
    r.font.strike = True
    p.add_run(". Areas are in m")
    r = p.add_run("2")
    r.font.superscript = True
    p.add_run(" and the water table reading is H")
    r = p.add_run("2")
    r.font.subscript = True
    p.add_run("O-adjusted.")

    p = doc.add_paragraph()
    r = p.add_run("Red means act now. ")
    r.font.color.rgb = RGBColor(0xC0, 0x39, 0x2B)
    r = p.add_run("Highlighted text was added after the site meeting. ")
    r.font.highlight_color = WD_COLOR_INDEX.YELLOW
    r = p.add_run("This run is set larger, ")
    r.font.size = Pt(16)
    r = p.add_run("this one in a serif face, ")
    r.font.name = "Georgia"
    r = p.add_run("and this one in a fixed-width face.")
    r.font.name = "Courier New"

    p = doc.add_paragraph("CENTRED-MARK This line is centred.")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p = doc.add_paragraph("RIGHT-MARK This line is set to the right.")
    p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    p = doc.add_paragraph(
        "JUSTIFIED-MARK This paragraph is justified, and it is long enough to wrap over "
        "several lines so that the justification has something to do. The bridge at "
        "Alder Lane carries the only road into the village from the north, and its "
        "parapet has moved a hand's width since the last survey was taken."
    )
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    p = doc.add_paragraph("INDENT-MARK This paragraph is indented from the left by an inch.")
    p.paragraph_format.left_indent = Inches(1)

    doc.add_heading("1.1 What was looked at", level=2)
    doc.add_paragraph("Bridges and culverts", style="List Bullet")
    doc.add_paragraph("Retaining walls", style="List Bullet")
    doc.add_paragraph("NESTED-MARK Dry stone, north bank", style="List Bullet 2")
    doc.add_paragraph("Mortared, churchyard", style="List Bullet 2")
    doc.add_paragraph("Footpaths and stiles", style="List Bullet")

    doc.add_heading("1.2 Order of work", level=2)
    doc.add_paragraph("NUMBERED-MARK Close Alder Lane bridge to vehicles", style="List Number")
    doc.add_paragraph("Prop the parapet", style="List Number")
    doc.add_paragraph("Rebuild from the springing", style="List Number")

    doc.add_heading("2. Costs", level=1)
    table = doc.add_table(rows=5, cols=4)
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    head = ["Structure", "Condition", "Estimate", "Year"]
    for i, text in enumerate(head):
        cell = table.rows[0].cells[i]
        cell.text = ""
        cell.paragraphs[0].add_run(text).bold = True
        shade(cell, "D9E2F3")
    rows = [
        ("Alder Lane bridge", "Poor", "48,200", "2027"),
        ("Churchyard wall", "Fair", "12,650", "2028"),
        ("Mill culvert", "Good", "3,100", "2029"),
    ]
    for r_i, row in enumerate(rows, start=1):
        for c_i, text in enumerate(row):
            cell = table.rows[r_i].cells[c_i]
            cell.text = text
            if c_i == 2:
                cell.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    merged = table.rows[4].cells[0].merge(table.rows[4].cells[1])
    merged.text = "MERGED-MARK Total, all structures"
    table.rows[4].cells[2].text = "63,950"
    table.rows[4].cells[2].paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    table.rows[4].cells[3].text = ""

    doc.add_paragraph()
    p = doc.add_paragraph("Name\tRole\tDays")
    p = doc.add_paragraph("TAB-MARK Imogen Hartley\tSurveyor\t11")

    doc.add_heading("3. Figures", level=1)
    doc.add_paragraph("PNG-MARK Estimated cost by structure:")
    doc.add_picture(io.BytesIO(chart_png()), width=Inches(4.5))
    doc.add_paragraph("JPEG-MARK Alder Lane at dusk:")
    doc.add_picture(io.BytesIO(photo_jpeg()), width=Inches(3.5))

    p = doc.add_paragraph("The full method is described at ")
    add_hyperlink(p, "LINK-MARK the council's survey page", "https://example.org/willowmere/survey")
    p.add_run(".")

    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)

    doc.add_heading("4. Notices in other languages", level=1)
    doc.add_paragraph("Accents: Größe, café, naïve, Łódź, fiancée, São Paulo, 25 €, “curly quotes”, ‘single’, 10 × 4 ÷ 2.")
    doc.add_paragraph("Hindi: पुल वाहनों के लिए बंद है।")
    doc.add_paragraph("Chinese: 桥梁对车辆关闭。")
    doc.add_paragraph("Japanese: 橋は車両通行止めです。")
    doc.add_paragraph("Korean: 다리는 차량 통행이 금지됩니다.")
    doc.add_paragraph("Russian: Мост закрыт для транспорта.")
    doc.add_paragraph("Greek: Η γέφυρα είναι κλειστή για οχήματα.")
    p = doc.add_paragraph("Arabic: الجسر مغلق أمام المركبات.")
    doc.add_paragraph("Emoji and astral: 🦆 𝒲illowmere")

    doc.add_heading("5. Closing", level=1)
    doc.add_paragraph("LAST-MARK This is the last paragraph of the document.")

    doc.core_properties.author = "Imogen Hartley"
    doc.core_properties.title = "Field Survey, Willowmere"
    doc.save(str(OUT / "master.docx"))


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    build()
    print("wrote", OUT / "master.docx")
