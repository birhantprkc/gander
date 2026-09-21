try {
  $("#result").pptxToHtml({
    pptxFileUrl: "/doc/file.pptx",
    slideMode: false,
    keyBoardShortCut: false,
    mediaProcess: true
  });
} catch (e) {
  vwError("Could not render this presentation", String(e));
}
var vwChecks = 0;
var vwPoll = setInterval(function () {
  vwChecks++;
  var slides = document.querySelectorAll("#result .slide");
  if (slides.length > 0) {
    /* Once the slides exist, because it is they that widen the layout viewport. */
    vwFitHeight();
    vwStatusDone();
    clearInterval(vwPoll);
  } else if (vwChecks > 40) {
    clearInterval(vwPoll);
    vwError(
      "Could not render this presentation",
      "This .pptx may use features the built-in renderer does not understand yet."
    );
  }
}, 500);
