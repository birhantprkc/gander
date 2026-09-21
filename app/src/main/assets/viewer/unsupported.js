document.getElementById("fname").textContent = vwName;
var hint = document.getElementById("hint");
if (vwExt === "doc" || vwExt === "ppt") {
  hint.textContent =
    "This is a legacy Office format (." + vwExt + "). If you re-save it as ." +
    (vwExt === "doc" ? "docx" : "pptx") + ", Gander can display it.";
} else {
  // Nothing useful to add; leave the card to the action below
  hint.style.display = "none";
}

document.getElementById("asText").addEventListener("click", function () {
  location.replace(
    "text.html?name=" + encodeURIComponent(vwName) + "&ext=" + encodeURIComponent(vwExt)
  );
});
