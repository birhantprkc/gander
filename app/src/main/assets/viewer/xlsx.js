vwFetchDoc("buffer")
  .then(function (buf) {
    var wb = XLSX.read(new Uint8Array(buf), { type: "array", cellDates: true });
    if (!wb.SheetNames.length) throw new Error("The workbook has no sheets");
    var tabs = document.getElementById("tabs");
    var sheetDiv = document.getElementById("sheet");
    function show(idx) {
      var ws = wb.Sheets[wb.SheetNames[idx]];
      sheetDiv.innerHTML = XLSX.utils.sheet_to_html(ws, { editable: false });
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
    vwStatusDone();
  })
  .catch(function (e) { vwError("Could not open this spreadsheet", String(e)); });
