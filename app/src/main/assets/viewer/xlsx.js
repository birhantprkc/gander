vwFetchDoc("buffer")
  .then(function (buf) {
    var wb = XLSX.read(new Uint8Array(buf), { type: "array", cellDates: true });
    if (!wb.SheetNames.length) throw new Error("The workbook has no sheets");
    var tabs = document.getElementById("tabs");
    var sheetDiv = document.getElementById("sheet");
    function show(idx) {
      var ws = wb.Sheets[wb.SheetNames[idx]];
      sheetDiv.innerHTML = XLSX.utils.sheet_to_html(ws, { editable: false });
      vwDisarmLinks(sheetDiv);
      var btns = tabs.querySelectorAll("button");
      for (var i = 0; i < btns.length; i++) btns[i].className = i === idx ? "active" : "";
    }
    wb.SheetNames.forEach(function (sn, idx) {
      var b = document.createElement("button");
      b.textContent = sn;
      b.onclick = function () { show(idx); };
      tabs.appendChild(b);
    });
    if (wb.SheetNames.length > 1) tabs.style.display = "";
    show(0);

    /* Find reaches every sheet, not only the one drawn: see find.js. A sheet not on the page is
       drawn into an element of its own to be read, the same drawing show() makes, so what is
       searched is what will be shown. */
    var current = 0;
    var drawn = show;
    show = function (idx) { current = idx; drawn(idx); };
    var offPage = [];
    window.vwFindParts = {
      count: function () { return wb.SheetNames.length; },
      root: function (i) {
        if (i === current) return sheetDiv;
        if (!offPage[i]) {
          offPage[i] = document.createElement("div");
          offPage[i].innerHTML = XLSX.utils.sheet_to_html(wb.Sheets[wb.SheetNames[i]], { editable: false });
        }
        return offPage[i];
      },
      show: function (i) { if (i !== current) show(i); return sheetDiv; },
      shown: function () { return current; }
    };
    vwStatusDone();
  })
  .catch(function (e) { vwError("Could not open this spreadsheet", String(e)); });
