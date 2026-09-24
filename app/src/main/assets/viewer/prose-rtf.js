"use strict";

/*
 * Rich Text Format, read into the pages prose-draw.js draws. Issue #13.
 *
 * An .rtf is one long line of text in which a backslash starts an instruction, \b for
 * bold and \par for the end of a paragraph, and braces mark how far an instruction
 * reaches: formatting set inside a group is forgotten where the group closes. A group
 * can also be a "destination", somewhere other than the page for its text to go: the
 * font table, a footnote, a picture written out in hexadecimal. So reading one is a
 * single pass over the bytes with a stack of states, one per open brace, and that is
 * what this is. There is no tree to build first and nothing is read twice.
 *
 * Every writer leaves a description of each paragraph that does not depend on knowing
 * its styles or its lists: the formatting is spelled out in full wherever it applies,
 * and a list item carries its own bullet or number as text, in a group made for readers
 * that do not count lists themselves. This reader is one of those, so a numbered list
 * shows the numbers its writer worked out and they cannot come out wrong.
 *
 * What is drawn: character and paragraph formatting, headings, lists, tables with
 * merged cells, borders and shading, PNG and JPEG pictures, footnotes and endnotes,
 * headers and footers, page size and margins, page breaks, and text in any of the
 * Windows code pages a file can declare. What is not: Windows metafile pictures, which
 * get a box saying so; drawn shapes, whose text is kept; tab stops set by hand; columns;
 * and a table inside a table, whose text is kept and laid out in the cell that holds it.
 */

/* Twentieths of a point, which is what Rich Text measures everything but type in. */
function rtfPt(twips) {
  return twips / 20;
}

/*
 * The Windows code page for each \fcharset a font can declare. Text older than Unicode
 * is bytes whose meaning depends on the font they are set in, which is how one file
 * holds Russian and Japanese at once.
 */
var RTF_CHARSETS = {
  0: 1252, 77: 10000, 128: 932, 129: 949, 130: 949, 134: 936, 136: 950, 161: 1253,
  162: 1254, 163: 1258, 177: 1255, 178: 1256, 186: 1257, 204: 1251, 222: 874, 238: 1250
};

/* What TextDecoder calls each of them. Anything else is read as 1252. */
var RTF_DECODER_NAMES = {
  874: "windows-874", 932: "shift_jis", 936: "gbk", 949: "euc-kr", 950: "big5",
  1250: "windows-1250", 1251: "windows-1251", 1252: "windows-1252", 1253: "windows-1253",
  1254: "windows-1254", 1255: "windows-1255", 1256: "windows-1256", 1257: "windows-1257",
  1258: "windows-1258", 10000: "macintosh", 866: "ibm866", 65001: "utf-8"
};

var RTF_DECODERS = {};

function rtfDecoder(codePage) {
  if (!RTF_DECODERS[codePage]) {
    var made;
    try {
      made = new TextDecoder(RTF_DECODER_NAMES[codePage] || "windows-1252");
    } catch (e) {
      made = new TextDecoder("windows-1252");
    }
    RTF_DECODERS[codePage] = made;
  }
  return RTF_DECODERS[codePage];
}

/* Whether a byte opens a two-byte character in one of the East Asian code pages. */
function rtfIsLead(codePage, b) {
  if (codePage === 932) return (b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC);
  if (codePage === 936 || codePage === 949 || codePage === 950) return b >= 0x81 && b <= 0xFE;
  return false;
}

/* The characters that are instructions rather than bytes. */
var RTF_CHARS = {
  emdash: "\u2014", endash: "\u2013", emspace: "\u2003", enspace: "\u2002",
  qmspace: "\u2005", bullet: "\u2022", lquote: "\u2018", rquote: "\u2019",
  ldblquote: "\u201C", rdblquote: "\u201D", zwj: "\u200D", zwnj: "\u200C",
  ltrmark: "\u200E", rtlmark: "\u200F", tab: "\t"
};

/*
 * Groups whose text is not the document's. The ones a writer marks with \* as safe to
 * ignore need no list, and any of those not named in rtfDestination is skipped whole.
 * These are the ones that come unmarked.
 */
var RTF_SKIPPED = {
  info: 1, filetbl: 1, template: 1, comment: 1, doccomm: 1, "private": 1, xe: 1, tc: 1,
  tcn: 1, rxe: 1, txe: 1, panose: 1, falt: 1, fname: 1, fontemb: 1, fontfile: 1,
  ftnsep: 1, ftnsepc: 1, ftncn: 1, aftnsep: 1, aftnsepc: 1, aftncn: 1, objdata: 1,
  objclass: 1, objname: 1, nonshppict: 1, shprslt: 1, sn: 1, bkmkstart: 1, bkmkend: 1,
  pn: 1, pnseclvl: 1, listtable: 1, listoverridetable: 1, revtbl: 1, rsidtbl: 1,
  atnid: 1, atnauthor: 1, annotation: 1, atrfstart: 1, atrfend: 1, atnref: 1, atndate: 1,
  atnicn: 1, atntime: 1, nextfile: 1, pgdsctbl: 1, themedata: 1, colorschememapping: 1,
  latentstyles: 1, datastore: 1, generator: 1, mmathPr: 1, xmlnstbl: 1, headerl: 1,
  headerf: 1, footerl: 1, footerf: 1, title: 1, subject: 1, author: 1, operator: 1,
  keywords: 1, category: 1, company: 1, manager: 1, creatim: 1, revtim: 1, printim: 1,
  buptim: 1, userprops: 1, formfield: 1, ffdata: 1, ffname: 1, ffdeftext: 1, ffl: 1,
  list: 1, listlevel: 1, leveltext: 1, levelnumbers: 1, listname: 1, listoverride: 1,
  lfolevel: 1, listpicture: 1, protusertbl: 1, docvar: 1, wgrffmtfilter: 1, mmath: 1
};

function rtfNewChar(state) {
  return { font: state.defaultFont, size: 12 };
}

function rtfNewPara() {
  return {};
}

function rtfCopy(bag) {
  var out = {};
  for (var k in bag) out[k] = bag[k];
  return out;
}

/*
 * Somewhere for paragraphs to go: the body, a header, a footer, a footnote. Each keeps
 * its own open paragraph and its own open table, since a footnote begins and ends in
 * the middle of a paragraph of the body and must leave that paragraph as it found it.
 */
function rtfFlow(container, top) {
  return { container: container, top: top, para: null, run: null, runKey: null,
    label: null, rows: [], cells: [], cell: null, fresh: true };
}

/* ------------------------------------------------------------------------------------
 * From formatting to the page
 * ---------------------------------------------------------------------------------- */

function rtfCharBag(state, cf) {
  var bag = {};
  if (cf.bold) bag.bold = true;
  if (cf.italic) bag.italic = true;
  if (cf.underline) bag.underline = cf.underline;
  if (cf.strike) bag.strike = cf.strike;
  if (cf.position) bag.position = cf.position;
  if (cf.caps) bag.caps = true;
  if (cf.smallCaps) bag.smallCaps = true;
  if (cf.spacing) bag.spacing = rtfPt(cf.spacing);
  if (cf.size && cf.size !== 12) bag.size = cf.size;
  if (cf.color && state.colors[cf.color]) bag.color = state.colors[cf.color];
  var mark = cf.highlight || cf.background;
  if (mark && state.colors[mark]) bag.background = state.colors[mark];
  var font = state.fonts[cf.font];
  if (font && cf.font !== state.defaultFont && !font.symbol) bag.font = font.css;
  return bag;
}

function rtfBorder(state, b) {
  if (!b || b.none || !b.style) return null;
  return { width: b.width != null ? rtfPt(b.width) : 0.75, style: b.style,
    color: (b.color && state.colors[b.color]) || "#000000" };
}

function rtfParaBag(state, pf) {
  var bag = {};
  if (pf.align) bag.align = pf.align;
  if (pf.left) bag.left = rtfPt(pf.left);
  if (pf.right) bag.right = rtfPt(pf.right);
  if (pf.first) bag.first = rtfPt(pf.first);
  if (pf.before) bag.before = rtfPt(pf.before);
  if (pf.after) bag.after = rtfPt(pf.after);
  if (pf.line) {
    // \slmult1 is a multiple of single spacing, counted in 240ths. Without it the
    // number is a height in twips, exact when negative and a minimum otherwise
    if (pf.lineMultiple) bag.line = Math.abs(pf.line) / 240;
    else if (pf.line < 0) bag.lineExact = rtfPt(-pf.line);
    else bag.lineAtLeast = rtfPt(pf.line);
  }
  if (pf.shade && state.colors[pf.shade]) bag.background = state.colors[pf.shade];
  if (pf.rtl) bag.rtl = true;
  var sides = ["Top", "Right", "Bottom", "Left"];
  for (var i = 0; i < 4; i++) {
    var b = rtfBorder(state, pf["border" + sides[i]]);
    if (b) { bag["border" + sides[i]] = b; bag["pad" + sides[i]] = 1; }
  }
  return bag;
}

/* ------------------------------------------------------------------------------------
 * Paragraphs and runs
 * ---------------------------------------------------------------------------------- */

function rtfOpenPara(state, flow) {
  if (flow.para) return flow.para;
  flow.para = document.createElement("p");
  flow.run = null;
  flow.runKey = null;
  return flow.para;
}

/*
 * Text for the page, in the formatting in force. Runs that share a format share a span,
 * because Word closes and reopens a group every few words for reasons of its own and a
 * span for each would triple the size of the page.
 */
function rtfText(state, text) {
  var g = state.group;
  if (!text || g.cf.hidden) return;
  var flow = g.flow;

  if (g.dest === "listtext") {
    if (!flow.label) flow.label = { text: "", cf: g.cf };
    flow.label.text += rtfSymbols(state, g.cf, text);
    return;
  }

  var para = rtfOpenPara(state, flow);
  var bag = rtfCharBag(state, g.cf);
  var name = vwProseClass(state.prose, bag);
  text = rtfSymbols(state, g.cf, text);
  if (flow.run && flow.runKey === name) {
    flow.run.appendData(text);
    return;
  }
  var node = document.createTextNode(text);
  if (name) {
    var span = document.createElement("span");
    span.className = name;
    span.appendChild(node);
    para.appendChild(span);
  } else {
    para.appendChild(node);
  }
  flow.run = node;
  flow.runKey = name;
}

function rtfSymbols(state, cf, text) {
  var font = state.fonts[cf.font];
  if (font && font.symbol) return vwProseSymbols(text, font.symbol);
  return /[\uF020-\uF0FF]/.test(text) ? vwProseSymbols(text, null) : text;
}

function rtfInlineNode(state, node) {
  var flow = state.group.flow;
  rtfOpenPara(state, flow).appendChild(node);
  flow.run = null;
  flow.runKey = null;
}

/*
 * Ends the open paragraph with the paragraph formatting in force, which is when Rich
 * Text means it to be read: a paragraph is whatever \par finds, not whatever its first
 * word found. always makes an empty one when none is open, which is a blank line.
 */
function rtfClosePara(state, flow, pf, always) {
  if (!flow.para && !always && !flow.label) return;
  var para = rtfOpenPara(state, flow);
  var bag = rtfParaBag(state, pf);

  var level = pf.outline != null && pf.outline < 9 ? pf.outline + 1
    : (state.headingStyles[pf.style] || 0);
  if (level) {
    var heading = document.createElement("h" + Math.min(level, 6));
    while (para.firstChild) heading.appendChild(para.firstChild);
    para = heading;
  }
  para.className = vwProseClass(state.prose, bag);

  if (flow.label) {
    var text = flow.label.text.replace(/^[\t ]+|[\t ]+$/g, "");
    var hang = pf.first < 0 ? rtfPt(-pf.first) : 0;
    if (text) vwProseLabel(state.prose, para, text, rtfCharBag(state, flow.label.cf), hang);
    flow.label = null;
  }

  if (pf.inTable) {
    if (!flow.cell) flow.cell = document.createDocumentFragment();
    flow.cell.appendChild(para);
  } else {
    rtfCloseTable(state, flow);
    if (flow.top && pf.pageBreakBefore && state.prose.body && state.prose.body.firstChild) {
      vwProseSheet(state.prose);
    }
    rtfTarget(state, flow).appendChild(para);
  }

  // A style can ask for no space between two paragraphs in the same style, which is
  // how the items of a list sit close while the list keeps its distance from the text
  // around it. Both margins go, since either alone would hold the gap open
  var last = flow.last;
  if (pf.contextual && last && last.el === para.previousElementSibling && last.style === pf.style) {
    last.el.style.marginBottom = "0";
    para.style.marginTop = "0";
  }
  flow.last = { el: para, style: pf.style };
  flow.para = null;
  flow.run = null;
  flow.runKey = null;
  flow.fresh = false;
}

/* ------------------------------------------------------------------------------------
 * Tables
 * ---------------------------------------------------------------------------------- */

/*
 * \cell: the paragraph in progress ends and so does the cell it is in. A cell with
 * nothing in it still counts, so an empty paragraph is made for one.
 */
function rtfEndCell(state, flow, pf) {
  // Whatever \cell ends was in the table, whether or not its writer said \intbl
  if (!pf.inTable) { pf = rtfCopy(pf); pf.inTable = true; }
  rtfClosePara(state, flow, pf, !flow.cell);
  flow.cells.push(flow.cell || document.createDocumentFragment());
  flow.cell = null;
}

/* \row: the cells gathered since the last one, with the row definition in force. */
function rtfEndRow(state, flow) {
  var def = state.row;
  flow.rows.push({
    cells: flow.cells,
    defs: def.cells.slice(),
    left: def.left || 0,
    pad: [def.padTop, def.padRight, def.padBottom, def.padLeft],
    gap: def.gap,
    align: def.align,
    height: def.height
  });
  flow.cells = [];
  flow.cell = null;
}

/*
 * Turns the rows gathered so far into a table, when something that is not in a table
 * comes along. It cannot be built row by row, because Rich Text gives every row its own
 * column edges and an HTML table has one set for all of them. So the edges of every row
 * are pooled into one grid, and each cell spans however many grid columns lie between
 * its own two edges.
 */
function rtfCloseTable(state, flow) {
  // Cells that never met their \row still hold text, which is kept as paragraphs
  if (flow.cells.length || flow.cell) {
    if (flow.cell) flow.cells.push(flow.cell);
    flow.rows.push({ cells: flow.cells, defs: [], left: 0, pad: [] });
    flow.cells = [];
    flow.cell = null;
  }
  if (!flow.rows.length) return;
  var rows = flow.rows;
  flow.rows = [];

  var edges = [];
  function edge(x) {
    for (var i = 0; i < edges.length; i++) if (Math.abs(edges[i] - x) <= 15) return;
    edges.push(x);
  }
  var r, c;
  for (r = 0; r < rows.length; r++) {
    edge(rows[r].left);
    for (c = 0; c < rows[r].defs.length; c++) edge(rows[r].defs[c].right);
  }
  edges.sort(function (a, b) { return a - b; });
  function column(x) {
    var best = 0;
    for (var i = 0; i < edges.length; i++) if (Math.abs(edges[i] - x) < Math.abs(edges[best] - x)) best = i;
    return best;
  }

  var table = document.createElement("table");
  var body = document.createElement("tbody");
  if (edges.length > 1) {
    var group = document.createElement("colgroup");
    for (var e = 1; e < edges.length; e++) {
      var col = document.createElement("col");
      col.style.width = vwProsePt(rtfPt(edges[e] - edges[e - 1]));
      group.appendChild(col);
    }
    table.appendChild(group);
    table.style.tableLayout = "fixed";
    table.style.width = vwProsePt(rtfPt(edges[edges.length - 1] - edges[0]));
    var first = rows[0];
    if (first.align === "center") { table.style.marginLeft = "auto"; table.style.marginRight = "auto"; }
    else if (first.align === "right") table.style.marginLeft = "auto";
    else if (first.left) table.style.marginLeft = vwProsePt(Math.max(-72, rtfPt(first.left)));
  }
  table.appendChild(body);

  // Which cells of each row are drawn, and where each starts and ends on the grid
  for (r = 0; r < rows.length; r++) {
    var row = rows[r];
    var at = row.left;
    row.spans = [];
    for (c = 0; c < row.cells.length; c++) {
      var def = row.defs[c] || {};
      var from = column(at);
      var to = def.right != null ? column(def.right) : from + 1;
      if (def.right != null) at = def.right;
      row.spans.push({ from: from, to: Math.max(to, from + 1), def: def, cell: row.cells[c] });
    }
    // A cell merged into the one before it gives that one its width and is not drawn
    for (c = row.spans.length - 1; c > 0; c--) {
      if (row.spans[c].def.mergedAcross) {
        row.spans[c - 1].to = Math.max(row.spans[c - 1].to, row.spans[c].to);
        row.spans.splice(c, 1);
      }
    }
  }

  for (r = 0; r < rows.length; r++) {
    var tr = document.createElement("tr");
    if (rows[r].height) tr.style.height = vwProsePt(rtfPt(Math.abs(rows[r].height)));
    for (c = 0; c < rows[r].spans.length; c++) {
      var span = rows[r].spans[c];
      if (span.def.mergedDown) continue;
      var td = document.createElement("td");
      if (span.to - span.from > 1) td.colSpan = span.to - span.from;

      // A cell that starts a merge downward takes in each cell under it that continues one
      if (span.def.mergesDown) {
        var down = 1;
        for (var below = r + 1; below < rows.length; below++) {
          var under = null;
          for (var k = 0; k < rows[below].spans.length; k++) {
            if (rows[below].spans[k].from === span.from) under = rows[below].spans[k];
          }
          if (!under || !under.def.mergedDown) break;
          down++;
        }
        if (down > 1) td.rowSpan = down;
      }

      // The space inside a cell: the row's, unless the cell says otherwise, and for
      // the sides an old writer's \trgaph, which is half the gap between two cells
      var bag = {};
      var sides = ["Top", "Right", "Bottom", "Left"];
      for (var s = 0; s < 4; s++) {
        var pad = span.def["pad" + sides[s]];
        if (pad == null) pad = rows[r].pad[s];
        if (pad == null && (s === 1 || s === 3)) pad = rows[r].gap;
        if (pad != null) bag["pad" + sides[s]] = rtfPt(Math.max(0, Math.min(pad, 1440)));
        var line = rtfBorder(state, span.def["border" + sides[s]]);
        if (line) bag["border" + sides[s]] = line;
      }
      if (span.def.shade && state.colors[span.def.shade]) bag.background = state.colors[span.def.shade];
      if (span.def.valign) bag.valign = span.def.valign;
      td.className = vwProseClass(state.prose, bag);
      td.appendChild(span.cell);
      tr.appendChild(td);
    }
    body.appendChild(tr);
  }
  rtfTarget(state, flow).appendChild(table);
}

/*
 * Where a flow's paragraphs go. For the body that is the current sheet, and the first
 * sheet is made here, at the first paragraph, because the paper size, the default font
 * and the header all arrive before it and a sheet is made with all three. A section
 * break waits here for the same reason: the next sheet is made when the section after
 * the break has its first paragraph, by which time it has said whether it starts a new
 * page at all and what its header is. A break with nothing after it makes no sheet.
 */
function rtfTarget(state, flow) {
  if (!flow.top) return flow.container;
  if (!state.prose.body) state.sheet();
  else if (state.sectionBreak && !state.sectionRunsOn && state.prose.body.firstChild) {
    vwProseSheet(state.prose);
  }
  state.sectionBreak = false;
  return state.prose.body;
}

/* ------------------------------------------------------------------------------------
 * Pictures
 * ---------------------------------------------------------------------------------- */

function rtfPicture(state, pict) {
  var bytes = pict.bytes.subarray(0, pict.length);
  var width = pict.goalWidth ? rtfPt(pict.goalWidth) * (pict.scaleX || 100) / 100
    : (pict.width ? pict.width * 0.75 * (pict.scaleX || 100) / 100 : 0);
  var height = pict.goalHeight ? rtfPt(pict.goalHeight) * (pict.scaleY || 100) / 100
    : (pict.height ? pict.height * 0.75 * (pict.scaleY || 100) / 100 : 0);
  rtfInlineNode(state, vwProsePicture(state.prose, bytes, width, height, ""));
}

function rtfPictByte(pict, b) {
  if (pict.length === pict.bytes.length) {
    var grown = new Uint8Array(pict.bytes.length * 2);
    grown.set(pict.bytes);
    pict.bytes = grown;
  }
  pict.bytes[pict.length++] = b;
}

/* ------------------------------------------------------------------------------------
 * Instructions
 * ---------------------------------------------------------------------------------- */

var RTF_UNDERLINES = {
  ul: true, uld: "dotted", uldash: "dashed", uldashd: "dashed", uldashdd: "dotted",
  uldb: "double", ulth: true, ulw: true, ulwave: "wavy", ulhwave: "wavy", ululdbwave: "wavy",
  ulthd: "dotted", ulthdash: "dashed", ulthdashd: "dashed", ulthdashdd: "dotted", ulldash: "dashed",
  ulthldash: "dashed"
};

var RTF_BORDER_STYLES = {
  brdrs: "solid", brdrth: "solid", brdrsh: "solid", brdrdb: "double", brdrdot: "dotted",
  brdrdash: "dashed", brdrhair: "solid", brdrdashsm: "dashed", brdrdashd: "dashed",
  brdrdashdd: "dotted", brdrtriple: "double", brdrtnthsg: "double", brdrthtnsg: "double",
  brdrtnthtnsg: "double", brdrtnthmg: "double", brdrthtnmg: "double", brdrtnthtnmg: "double",
  brdrtnthlg: "double", brdrthtnlg: "double", brdrtnthtnlg: "double", brdrwavy: "solid",
  brdrwavydb: "double", brdrdashdotstr: "dashed", brdremboss: "ridge", brdrengrave: "groove",
  brdrinset: "inset", brdroutset: "outset"
};

/*
 * A destination: what to do with a group that opens with this word. Answers the name of
 * the mode the group is read in, or null if the word is not one that starts a group.
 */
function rtfDestination(word) {
  switch (word) {
    case "fonttbl": case "colortbl": case "stylesheet": case "pict": case "fldinst":
    case "listtext": case "footnote": case "header": case "footer": case "headerr":
    case "footerr":
      return word;
    case "pntext":
      return "listtext";
    case "sv":
      return "shapevalue";
    case "shppict": case "shpinst": case "shp": case "shptxt": case "fldrslt": case "field":
    case "ud": case "result": case "object": case "shpgrp": case "sp":
      return "same";
    default:
      return RTF_SKIPPED[word] ? "skip" : null;
  }
}

function rtfWord(state, word, param, has) {
  var g = state.group;
  var on = !has || param !== 0;

  // The word that opens a group says where the group's text goes
  if (g.fresh) {
    g.fresh = false;
    var dest = rtfDestination(word);
    if (dest === "same") dest = null;
    if (g.starred && !dest && !/^(shppict|shpinst|ud|fldrslt)$/.test(word)) dest = "skip";
    g.starred = false;
    if (dest) {
      rtfEnter(state, dest, word);
      if (dest !== "pict") return;
    }
  }
  if (g.dest === "skip") {
    if (word === "bin" && param > 0) state.at += param;
    return;
  }

  if (g.dest === "pict") { rtfPictWord(state, word, param); return; }
  if (g.dest === "fonttbl") { rtfFontWord(state, word, param); return; }
  if (g.dest === "colortbl") {
    if (word === "red") state.rgb[0] = param;
    else if (word === "green") state.rgb[1] = param;
    else if (word === "blue") state.rgb[2] = param;
    return;
  }
  if (g.dest === "stylesheet") {
    if (word === "s") state.styleNumber = param;
    return;
  }
  if (g.dest === "shapevalue" || g.dest === "fldinst") return;

  var cf, pf;
  if (RTF_CHARS[word]) { rtfText(state, RTF_CHARS[word]); return; }

  switch (word) {
    /* Characters */
    case "plain": g.cf = rtfNewChar(state); return;
    case "b": cf = rtfCopy(g.cf); cf.bold = on; g.cf = cf; return;
    case "i": cf = rtfCopy(g.cf); cf.italic = on; g.cf = cf; return;
    case "strike": cf = rtfCopy(g.cf); cf.strike = on; g.cf = cf; return;
    case "striked": cf = rtfCopy(g.cf); cf.strike = on ? "double" : false; g.cf = cf; return;
    case "ulnone": cf = rtfCopy(g.cf); cf.underline = false; g.cf = cf; return;
    case "caps": cf = rtfCopy(g.cf); cf.caps = on; g.cf = cf; return;
    case "scaps": cf = rtfCopy(g.cf); cf.smallCaps = on; g.cf = cf; return;
    case "v": case "deleted": cf = rtfCopy(g.cf); cf.hidden = on; g.cf = cf; return;
    case "super": cf = rtfCopy(g.cf); cf.position = on ? "super" : null; g.cf = cf; return;
    case "sub": cf = rtfCopy(g.cf); cf.position = on ? "sub" : null; g.cf = cf; return;
    case "nosupersub": cf = rtfCopy(g.cf); cf.position = null; g.cf = cf; return;
    case "f": cf = rtfCopy(g.cf); cf.font = param; g.cf = cf; return;
    case "fs": cf = rtfCopy(g.cf); cf.size = param / 2; g.cf = cf; return;
    case "cf": cf = rtfCopy(g.cf); cf.color = param; g.cf = cf; return;
    case "highlight": cf = rtfCopy(g.cf); cf.highlight = param; g.cf = cf; return;
    case "cb": case "chcbpat": cf = rtfCopy(g.cf); cf.background = param; g.cf = cf; return;
    case "expndtw": cf = rtfCopy(g.cf); cf.spacing = param; g.cf = cf; return;
    case "uc": g.uc = Math.max(0, param); return;
    case "u":
      rtfText(state, String.fromCharCode(param < 0 ? param + 65536 : param));
      state.skip = g.uc;
      return;

    /* Paragraphs */
    case "par": rtfClosePara(state, g.flow, g.pf, true); return;
    case "pard": g.pf = rtfNewPara(); return;
    case "ql": pf = rtfCopy(g.pf); pf.align = "left"; g.pf = pf; return;
    case "qr": pf = rtfCopy(g.pf); pf.align = "right"; g.pf = pf; return;
    case "qc": pf = rtfCopy(g.pf); pf.align = "center"; g.pf = pf; return;
    case "qj": case "qd": pf = rtfCopy(g.pf); pf.align = "justify"; g.pf = pf; return;
    case "li": case "lin": pf = rtfCopy(g.pf); pf.left = param; g.pf = pf; return;
    case "ri": case "rin": pf = rtfCopy(g.pf); pf.right = param; g.pf = pf; return;
    case "fi": pf = rtfCopy(g.pf); pf.first = param; g.pf = pf; return;
    case "sb": pf = rtfCopy(g.pf); pf.before = param; g.pf = pf; return;
    case "sa": pf = rtfCopy(g.pf); pf.after = param; g.pf = pf; return;
    case "sl": pf = rtfCopy(g.pf); pf.line = param; g.pf = pf; return;
    case "slmult": pf = rtfCopy(g.pf); pf.lineMultiple = param === 1; g.pf = pf; return;
    case "s": pf = rtfCopy(g.pf); pf.style = param; g.pf = pf; return;
    case "outlinelevel": pf = rtfCopy(g.pf); pf.outline = param; g.pf = pf; return;
    case "intbl": pf = rtfCopy(g.pf); pf.inTable = true; g.pf = pf; return;
    case "pagebb": pf = rtfCopy(g.pf); pf.pageBreakBefore = on; g.pf = pf; return;
    case "rtlpar": pf = rtfCopy(g.pf); pf.rtl = true; g.pf = pf; return;
    case "ltrpar": pf = rtfCopy(g.pf); pf.rtl = false; g.pf = pf; return;
    case "cbpat": pf = rtfCopy(g.pf); pf.shade = param; g.pf = pf; return;
    case "contextualspace": pf = rtfCopy(g.pf); pf.contextual = on; g.pf = pf; return;

    case "line": rtfInlineNode(state, document.createElement("br")); return;
    case "page":
      rtfClosePara(state, g.flow, g.pf, false);
      if (g.flow.top && rtfTarget(state, g.flow).firstChild) vwProseSheet(state.prose);
      return;
    case "sect":
      // The kind of break, and the header over the sheet it makes, belong to the section
      // that starts here, and are written after this word; so the sheet waits for that
      // section's first paragraph, in rtfTarget
      rtfClosePara(state, g.flow, g.pf, true);
      if (g.flow.top) {
        rtfCloseTable(state, g.flow);
        state.sectionBreak = true;
      }
      return;
    case "sbknone": state.sectionRunsOn = true; return;
    case "sbkpage": case "sbkeven": case "sbkodd": case "sectd": state.sectionRunsOn = false; return;

    /* Tables */
    case "trowd": state.row = { cells: [], pending: {} }; return;
    case "cellx":
      state.row.pending.right = param;
      state.row.cells.push(state.row.pending);
      state.row.pending = {};
      state.border = null;
      return;
    case "trleft": state.row.left = param; return;
    case "trgaph": state.row.gap = param; return;
    case "trpaddl": state.row.padLeft = param; return;
    case "trpaddr": state.row.padRight = param; return;
    case "trpaddt": state.row.padTop = param; return;
    case "trpaddb": state.row.padBottom = param; return;
    case "clpadl": state.row.pending.padLeft = param; return;
    case "clpadr": state.row.pending.padRight = param; return;
    case "clpadt": state.row.pending.padTop = param; return;
    case "clpadb": state.row.pending.padBottom = param; return;
    case "trrh": state.row.height = param; return;
    case "trqc": state.row.align = "center"; return;
    case "trqr": state.row.align = "right"; return;
    case "clmgf": state.row.pending.mergesAcross = true; return;
    case "clmrg": state.row.pending.mergedAcross = true; return;
    case "clvmgf": state.row.pending.mergesDown = true; return;
    case "clvmrg": state.row.pending.mergedDown = true; return;
    case "clcbpat": state.row.pending.shade = param; return;
    case "clvertalc": state.row.pending.valign = "middle"; return;
    case "clvertalb": state.row.pending.valign = "bottom"; return;
    case "cell": case "nestcell": rtfEndCell(state, g.flow, g.pf); return;
    case "row": rtfEndRow(state, g.flow); return;
    case "nestrow": return;

    /* Borders: a word that names a side, then words that describe the line on it */
    case "clbrdrt": state.border = state.row.pending.borderTop = {}; return;
    case "clbrdrr": state.border = state.row.pending.borderRight = {}; return;
    case "clbrdrb": state.border = state.row.pending.borderBottom = {}; return;
    case "clbrdrl": state.border = state.row.pending.borderLeft = {}; return;
    case "brdrt": pf = rtfCopy(g.pf); state.border = pf.borderTop = {}; g.pf = pf; return;
    case "brdrr": pf = rtfCopy(g.pf); state.border = pf.borderRight = {}; g.pf = pf; return;
    case "brdrb": pf = rtfCopy(g.pf); state.border = pf.borderBottom = {}; g.pf = pf; return;
    case "brdrl": pf = rtfCopy(g.pf); state.border = pf.borderLeft = {}; g.pf = pf; return;
    case "box":
      pf = rtfCopy(g.pf);
      state.border = pf.borderTop = pf.borderRight = pf.borderBottom = pf.borderLeft = {};
      g.pf = pf;
      return;
    case "brdrw": if (state.border) state.border.width = param; return;
    case "brdrcf": if (state.border) state.border.color = param; return;
    case "brdrnone": case "brdrnil": case "brdrtbl": if (state.border) state.border.none = true; return;

    /* The page */
    case "paperw": state.layout.width = rtfPt(param); return;
    case "paperh": state.layout.height = rtfPt(param); return;
    case "margl": state.layout.left = rtfPt(param); return;
    case "margr": state.layout.right = rtfPt(param); return;
    case "margt": state.layout.top = rtfPt(param); return;
    case "margb": state.layout.bottom = rtfPt(param); return;

    /* The document */
    case "ansicpg": state.codePage = param; return;
    case "mac": state.codePage = 10000; return;
    case "pc": case "pca": state.codePage = 866; return;
    case "deff":
      state.defaultFont = param;
      g.cf = rtfCopy(g.cf);
      g.cf.font = param;
      return;
    case "chftn": rtfNoteMark(state); return;
    case "bin": rtfBinary(state, param); return;
    case "upr": g.unicodeTwin = true; return;
  }

  if (RTF_UNDERLINES[word] !== undefined) {
    cf = rtfCopy(g.cf);
    cf.underline = on ? RTF_UNDERLINES[word] : false;
    g.cf = cf;
  } else if (RTF_BORDER_STYLES[word] && state.border) {
    state.border.style = RTF_BORDER_STYLES[word];
  }
}

function rtfBinary(state, count) {
  var g = state.group;
  var end = Math.min(state.bytes.length, state.at + Math.max(0, count));
  if (g.dest === "pict" && state.pict) {
    for (var i = state.at; i < end; i++) rtfPictByte(state.pict, state.bytes[i]);
  }
  state.at = end;
}

function rtfPictWord(state, word, param) {
  // A \pict group inside a \pict group, which no writer makes but a damaged file can,
  // takes the picture with it when it closes and leaves the outer group with none
  var p = state.pict;
  if (!p) return;
  if (word === "picw") p.width = param;
  else if (word === "pich") p.height = param;
  else if (word === "picwgoal") p.goalWidth = param;
  else if (word === "pichgoal") p.goalHeight = param;
  else if (word === "picscalex") p.scaleX = param;
  else if (word === "picscaley") p.scaleY = param;
  else if (word === "bin") rtfBinary(state, param);
}

function rtfFontWord(state, word, param) {
  var f = state.font;
  if (word === "f") { f.number = param; f.named = true; }
  else if (word === "fcharset") f.charset = param;
  else if (word === "cpg") f.codePage = param;
  else if (/^f(nil|roman|swiss|modern|script|decor|tech|bidi)$/.test(word)) f.kind = word.slice(1);
}

/*
 * The footnote mark, which is an instruction and not a number: the writer leaves the
 * counting to the reader. It comes twice, once in the text where the note is called and
 * once at the head of the note itself, and the second is the same number as the first.
 */
function rtfNoteMark(state) {
  var g = state.group;
  if (!g.inNote) state.noteNumber++;
  var mark = document.createElement("sup");
  if (!g.inNote) mark.className = "vw-noteref";
  mark.textContent = String(state.noteNumber);
  rtfInlineNode(state, mark);
  if (g.inNote) rtfText(state, " ");
}

/* What opening a destination sets up. Closing it is rtfLeave. */
function rtfEnter(state, dest, word) {
  var g = state.group;
  g.dest = dest;
  g.opened = dest;

  if (dest === "pict") {
    state.pict = { bytes: new Uint8Array(4096), length: 0, nibble: -1 };
  } else if (dest === "fonttbl") {
    state.font = { name: "" };
  } else if (dest === "colortbl") {
    state.rgb = [null, null, null];
  } else if (dest === "footnote") {
    g.flow = rtfFlow(document.createElement("div"), false);
    g.inNote = true;
    g.pf = rtfNewPara();
  } else if (dest === "header" || dest === "headerr" || dest === "footer" || dest === "footerr") {
    g.flow = rtfFlow(document.createElement("div"), false);
    g.pf = rtfNewPara();
    g.dest = "body";
    g.opened = dest.charAt(0) === "h" ? "header" : "footer";
  } else if (dest === "listtext") {
    g.flow.label = null;
  }
}

function rtfLeave(state, g) {
  if (g.opened === "pict") {
    var outer = state.group;
    if (state.pict && outer.dest !== "skip" && state.pict.length) rtfPicture(state, state.pict);
    state.pict = null;
  } else if (g.opened === "footnote") {
    rtfClosePara(state, g.flow, g.pf, false);
    rtfCloseTable(state, g.flow);
    state.notes.push(g.flow.container);
  } else if (g.opened === "header" || g.opened === "footer") {
    rtfClosePara(state, g.flow, g.pf, false);
    rtfCloseTable(state, g.flow);
    if (g.flow.container.textContent.trim() || g.flow.container.querySelector("img")) {
      state.layout[g.opened + "Source"] = g.flow.container;
    }
  }
}

/* ------------------------------------------------------------------------------------
 * Reading
 * ---------------------------------------------------------------------------------- */

/*
 * Bytes waiting to become text. They wait because one character can be two of them, a
 * lead byte and a trail byte, and the two can arrive as \'83 and then a plain "A": the
 * trail byte of a Japanese character is often an ordinary ASCII letter, and some
 * writers only escape the bytes that need escaping.
 */
function rtfFlushBytes(state) {
  if (!state.pending.length) return;
  var bytes = new Uint8Array(state.pending);
  state.pending = [];
  var font = state.fonts[state.group.cf.font];
  var text;
  if (font && font.symbol) {
    text = "";
    for (var i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
  } else {
    text = rtfDecoder(state.pendingCodePage).decode(bytes);
  }
  rtfTextOut(state, text);
}

function rtfCodePage(state) {
  var font = state.fonts[state.group.cf.font];
  return (font && font.codePage) || state.codePage;
}

function rtfByte(state, b) {
  if (state.skip > 0) { state.skip--; return; }
  var codePage = rtfCodePage(state);
  if (state.pending.length && codePage !== state.pendingCodePage) rtfFlushBytes(state);
  state.pendingCodePage = codePage;
  state.pending.push(b);
}

/* Whether the bytes waiting end half way through a two-byte character. */
function rtfAwaitsTrail(state) {
  var p = state.pending;
  var half = false;
  for (var i = 0; i < p.length; i++) {
    if (half) half = false;
    else if (rtfIsLead(state.pendingCodePage, p[i])) half = true;
  }
  return half;
}

/* Text on its way to wherever the open group sends it. */
function rtfTextOut(state, text) {
  var g = state.group;
  if (g.dest === "skip" || g.dest === "shapevalue" || g.dest === "fldinst" ||
      g.dest === "pict" || g.dest === "stylesheet-skip") return;

  if (g.dest === "fonttbl") {
    var f = state.font;
    var end = text.indexOf(";");
    f.name += end < 0 ? text : text.slice(0, end);
    if (end >= 0) rtfEndFont(state);
    return;
  }
  if (g.dest === "colortbl") {
    for (var i = 0; i < text.length; i++) {
      if (text.charAt(i) !== ";") continue;
      state.colors.push(state.rgb[0] == null ? null
        : vwProseRgb(state.rgb[0], state.rgb[1] || 0, state.rgb[2] || 0));
      state.rgb = [null, null, null];
    }
    return;
  }
  if (g.dest === "stylesheet") {
    state.styleName += text;
    var semi = state.styleName.indexOf(";");
    if (semi >= 0) {
      var heading = /^\s*heading (\d)\s*$/i.exec(state.styleName.slice(0, semi));
      if (heading && state.styleNumber != null) state.headingStyles[state.styleNumber] = +heading[1];
      state.styleName = "";
      state.styleNumber = null;
    }
    return;
  }
  rtfText(state, text);
}

function rtfEndFont(state) {
  var f = state.font;
  if (f.named) {
    var name = f.name.trim();
    var codePage = f.codePage || (f.charset != null && f.charset !== 1 ? RTF_CHARSETS[f.charset] : null);
    state.fonts[f.number] = {
      css: vwProseFont(name, f.kind),
      codePage: codePage || null,
      symbol: f.charset === 2 || vwProseSymbolFont(name) ? (vwProseSymbolFont(name) || "symbol") : null
    };
  }
  state.font = { name: "" };
}

/*
 * Draws the document in buffer into container and answers the prose it drew, the way
 * vwReadOdt does. Nothing here waits for anything, but a promise keeps the three
 * readers alike.
 */
function vwReadRtf(buffer, container) {
  var bytes = new Uint8Array(buffer);
  var prose = vwProseOpen(container);
  var state = {
    prose: prose, bytes: bytes, at: 0,
    fonts: {}, colors: [], headingStyles: {}, notes: [],
    codePage: 1252, defaultFont: 0, noteNumber: 0,
    pending: [], pendingCodePage: 1252, skip: 0,
    layout: { width: 612, height: 792, left: 90, right: 90, top: 72, bottom: 72 },
    row: { cells: [], pending: {} }, border: null, styleName: "", styleNumber: null,
    sectionRunsOn: false, sectionBreak: false, pict: null, font: null, rgb: null, sheet: null
  };
  var body = rtfFlow(null, true);
  state.group = { dest: "body", cf: rtfNewChar(state), pf: rtfNewPara(), uc: 1, flow: body, fresh: false };
  var stack = [];

  /*
   * The first sheet waits for the first word of the body, because the paper size, the
   * default font and the header all come before it and a sheet is made with all three.
   */
  state.sheet = function () {
    var layout = state.layout;
    layout.header = function (el) {
      if (layout.headerSource) el.appendChild(layout.headerSource.cloneNode(true));
    };
    layout.footer = function (el) {
      if (layout.footerSource) el.appendChild(layout.footerSource.cloneNode(true));
    };
    var font = state.fonts[state.defaultFont];
    prose.base = vwProseClass(prose, { font: font && !font.symbol ? font.css : null, size: 12 });
    vwProseSheet(prose, layout);
  };

  var n = bytes.length;
  while (state.at < n) {
    var b = bytes[state.at++];
    var g = state.group;

    if (b === 0x7B) {                       // {
      rtfFlushBytes(state);
      state.skip = 0;
      if (stack.length > 2000) throw new Error("This document is nested too deeply to read.");
      stack.push(g);
      var twin = g.unicodeTwin;
      g.unicodeTwin = false;
      state.group = {
        dest: twin ? "skip" : g.dest, cf: g.cf, pf: g.pf, uc: g.uc, flow: g.flow,
        inNote: g.inNote, fresh: true, starred: false, opened: null
      };
    } else if (b === 0x7D) {                // }
      rtfFlushBytes(state);
      state.skip = 0;
      if (!stack.length) break;
      state.group = stack.pop();
      rtfLeave(state, g);
      // The brace that closes {\rtf1 ends the document, whatever follows it
      if (!stack.length) break;
    } else if (b === 0x5C) {                // backslash
      var c = bytes[state.at++];
      if (c === 0x27) {                     // \'hh
        var hex = parseInt(String.fromCharCode(bytes[state.at], bytes[state.at + 1]), 16);
        state.at += 2;
        g.fresh = false;
        // Every group whose text is kept takes the byte, a footnote's as much as the
        // body's, and so uses up the fallback a \u character before it leaves
        if (!isNaN(hex) && g.dest !== "skip" && g.dest !== "shapevalue" &&
            g.dest !== "fldinst" && g.dest !== "pict") {
          rtfByte(state, hex);
        }
      } else if ((c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A)) {
        var start = state.at - 1;
        while (state.at < n && ((bytes[state.at] >= 0x61 && bytes[state.at] <= 0x7A) ||
               (bytes[state.at] >= 0x41 && bytes[state.at] <= 0x5A))) state.at++;
        var word = "";
        for (var w = start; w < state.at; w++) word += String.fromCharCode(bytes[w]);
        var has = false;
        var param = 0;
        var sign = 1;
        if (bytes[state.at] === 0x2D) { sign = -1; state.at++; }
        while (state.at < n && bytes[state.at] >= 0x30 && bytes[state.at] <= 0x39) {
          param = param * 10 + (bytes[state.at++] - 0x30);
          has = true;
        }
        param *= sign;
        if (bytes[state.at] === 0x20) state.at++;
        rtfFlushBytes(state);
        if (word !== "u") state.skip = 0;
        rtfWord(state, word, param, has);
      } else if (c === 0x2A) {              // \*
        g.starred = true;
      } else if (c === 0x0A || c === 0x0D) {
        // A backslash before a line end is \par, in a footnote as in the body
        rtfFlushBytes(state);
        if (g.dest === "body" || g.dest === "footnote") rtfClosePara(state, g.flow, g.pf, true);
      } else {
        g.fresh = false;
        var symbol = c === 0x7E ? "\u00A0" : c === 0x2D ? "\u00AD" : c === 0x5F ? "\u2011"
          : (c === 0x5C || c === 0x7B || c === 0x7D) ? String.fromCharCode(c) : "";
        if (symbol) {
          rtfFlushBytes(state);
          if (state.skip > 0) state.skip--;
          else rtfTextOut(state, symbol);
        }
      }
    } else if (b === 0x0A || b === 0x0D) {
      // A line end in the file is the file's own business and no part of the text
    } else if (g.dest === "pict") {
      var digit = b <= 0x39 ? b - 0x30 : (b | 0x20) - 0x57;
      if (digit >= 0 && digit <= 15 && state.pict) {
        if (state.pict.nibble < 0) state.pict.nibble = digit;
        else { rtfPictByte(state.pict, state.pict.nibble * 16 + digit); state.pict.nibble = -1; }
      }
    } else if (g.dest === "skip" || g.dest === "shapevalue" || g.dest === "fldinst") {
      g.fresh = false;
    } else {
      g.fresh = false;
      if (state.skip > 0) { state.skip--; continue; }
      if (b >= 0x80 || (state.pending.length && rtfAwaitsTrail(state))) {
        rtfByte(state, b);
      } else {
        rtfFlushBytes(state);
        // A run of plain ASCII goes out in one piece
        var from = state.at - 1;
        while (state.at < n) {
          var next = bytes[state.at];
          if (next === 0x5C || next === 0x7B || next === 0x7D || next === 0x0A ||
              next === 0x0D || next >= 0x80) break;
          state.at++;
        }
        var plain = "";
        for (var a = from; a < state.at; a += 8192) {
          plain += String.fromCharCode.apply(null, bytes.subarray(a, Math.min(a + 8192, state.at)));
        }
        rtfTextOut(state, plain);
      }
    }
  }

  rtfFlushBytes(state);
  rtfClosePara(state, body, state.group.pf, false);
  rtfCloseTable(state, body);
  if (!prose.body) state.sheet();

  if (state.notes.length) {
    var area = vwProseNotes(prose);
    for (var i = 0; i < state.notes.length; i++) area.appendChild(state.notes[i]);
  }
  return Promise.resolve(prose);
}
