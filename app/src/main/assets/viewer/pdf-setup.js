/*
 * Classic on purpose, and loaded ahead of pdf.mjs. What draws a PDF is an ES module,
 * and a WebView too old to parse one never runs a line of it, so no handler
 * inside the module can report that the module never loaded. What the reader
 * gets instead is app.js's window.onerror: "Something went wrong while
 * rendering", followed by a parser complaint naming a file they have never heard
 * of. Checked in headless Chrome by importing a module with syntax it rejects.
 * So whatever says "your WebView is too old" cannot itself live in the module.
 *
 * The module is fetched whatever happens here, and below Chromium 94, which added
 * the class static blocks pdf.js uses, it cannot even be parsed. That failure lands
 * after the card is drawn, which is why vwWebViewTooOld takes window.onerror away
 * with it: on the WebView 64 of issue #31 it swapped the card for "Unexpected
 * token .". The card itself, and what Kotlin sends it, is described in app.js.
 *
 * Global on purpose: pdf.mjs reads it, and it has to be set by a script
 * that is guaranteed to have parsed.
 */
var vwPdfBlocked = vwWebViewTooOld("PDFs");

/*
 * Night mode, and set here rather than in the module for the same reason as everything
 * else in this script: the module is fetched and parsed after this runs, and every page
 * laid out in between would be a white rectangle. Global on purpose, the module reads
 * it once and owns it from then on.
 */
var vwNight = vwParams.get("night") === "1";
if (vwNight) document.documentElement.classList.add("vw-night");
