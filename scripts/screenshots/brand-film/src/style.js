// Palette, texture and the icon set. Every colour here is one Gander already uses: the two
// grounds are the launcher's cream and the store listing's warm near-black, the accents are
// the site's red and the listing's coral, and the format colours are WELCOME_BADGES in
// Listing.kt. The pastels are those same format colours let down into the cream.
(function () {
  const F = window.F;

  const C = (F.C = {
    cream: "#F6F1E6",
    creamDeep: "#EDE5D4",
    paper: "#FFFDF8",
    surface: "#FFFBF3",
    appbar: "#F7E7DB",
    ink: "#1A222C",
    inkSoft: "#4D5865",
    inkFaint: "#8E887C",
    goose: "#262A2E",
    cheek: "#FDFBF5",
    dark: "#17130C",
    darkLift: "#231D13",
    onDark: "#F8EFE0",
    muted: "#CFC5B2",
    dim: "#6F6758",
    coral: "#E2795F",
    red: "#AF2D18",
    fold: "#C9CFD6",
    skel: "#D9DEE5",
    skelWarm: "#E6DFD2",
    fmt: {
      PDF: "#B3261E", DOC: "#1565C0", XLS: "#2E7D32", PPT: "#B25000", IMG: "#7B1FA2",
      VID: "#AD1457", AUD: "#00838F", MD: "#455A64", TXT: "#616161", ZIP: "#8A6D1F",
    },
  });
  // A format colour as a pastel on the cream ground.
  F.tint = (hex, k = 0.27) => F.mixHex(C.cream, hex, k);

  // ---- the hand-drawn line --------------------------------------------------------------
  // Not an SVG displacement filter: that moves pixels, and leaves every near-straight edge
  // as a staircase with no antialiasing. Instead each registered path is sampled once into
  // points, and on every boil step the points are pushed through smooth seeded noise and
  // written back out. The edge stays a real vector edge at any size.
  const hash = (ix, iy, seed) => {
    let h = (Math.imul(ix, 374761393) + Math.imul(iy, 668265263) + Math.imul(seed, 1442695041)) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
  };
  F.noise2 = (x, y, seed) => {
    const ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
    const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
    const a = hash(ix, iy, seed), b = hash(ix + 1, iy, seed), c = hash(ix, iy + 1, seed), d = hash(ix + 1, iy + 1, seed);
    return (a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy) * 2 - 1;
  };
  const wobs = [];
  // amp and wl (the noise's wavelength) are in the path's own units, as is step.
  F.wob = (el, o = {}) => {
    wobs.push({ el, amp: o.amp ?? 2, wl: o.wl ?? 80, step: o.step ?? 5, subs: null });
    return el;
  };
  F.prepWobs = (svg) => {
    const probe = F.el("path", null, svg.querySelector("defs"));
    for (const w of wobs) {
      const d = w.el.getAttribute("d") || "";
      w.subs = (d.match(/M[^M]+/g) || []).map((sub) => {
        probe.setAttribute("d", sub);
        const len = probe.getTotalLength(), closed = /z\s*$/i.test(sub);
        const n = Math.max(3, Math.ceil(len / w.step)), pts = [];
        for (let i = 0; i < (closed ? n : n + 1); i++) {
          const p = probe.getPointAtLength((len * i) / n);
          pts.push([p.x, p.y]);
        }
        return { pts, closed };
      });
    }
    probe.remove();
  };
  F.seed = -1;
  // The line boils at about eight drawings a second, the way a traced cel does. Holding
  // each drawing for several frames is the point: a new one every frame reads as shimmer.
  F.boil = (t) => {
    const s = Math.floor(t * 8) + 1;
    if (s === F.seed) return;
    F.seed = s;
    for (const w of wobs) {
      let d = "";
      for (const sub of w.subs) {
        d += "M" + sub.pts.map(([x, y]) =>
          (x + F.noise2(x / w.wl, y / w.wl, s) * w.amp).toFixed(2) + "," +
          (y + F.noise2(x / w.wl + 31.7, y / w.wl + 17.3, s) * w.amp).toFixed(2)).join("L") + (sub.closed ? "Z" : "");
      }
      w.el.setAttribute("d", d);
    }
  };

  // ---- defs: the paper grain ---------------------------------------------------------------
  F.buildDefs = (svg) => {
    const defs = F.el("defs", null, svg);
    // One seeded tile of black and white specks, so it sits on cream and on dark alike
    // without a blend mode. Static, which is also what keeps it cheap to encode.
    const size = 320, cv = document.createElement("canvas");
    cv.width = cv.height = size;
    const ctx = cv.getContext("2d"), img = ctx.createImageData(size, size), r = F.rng(7);
    for (let i = 0; i < size * size; i++) {
      const v = r(), a = r();
      img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v < 0.5 ? 0 : 255;
      img.data[i * 4 + 3] = Math.floor(a * a * 12);
    }
    ctx.putImageData(img, 0, 0);
    const pat = F.el("pattern", { id: "grain", width: size, height: size, patternUnits: "userSpaceOnUse" }, defs);
    F.el("image", { href: cv.toDataURL("image/png"), width: size, height: size }, pat);
    return defs;
  };

  // ---- icons, drawn on a 24 grid as strokes -----------------------------------------------
  const I = {
    back: ["M20 12H5", "M11 6l-6 6 6 6"],
    search: ["M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M16.2 16.2L21 21"],
    share: [
      "M15.4 5a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0", "M3.4 12a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0",
      "M15.4 19a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0-5.2 0", "M8.3 10.7l7.4-4.4", "M8.3 13.3l7.4 4.4",
    ],
    camera: [
      "M3 8.5a2 2 0 0 1 2-2h2.2l1.4-2h6.8l1.4 2H19a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z",
      "M8.4 13a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0-7.2 0",
    ],
    mic: ["M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3Z", "M6 11a6 6 0 0 0 12 0", "M12 17v4", "M9 21h6"],
    pin: ["M12 21.5s-6.5-5.8-6.5-11.3a6.5 6.5 0 0 1 13 0c0 5.5-6.5 11.3-6.5 11.3Z", "M9.6 10a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0-4.8 0"],
    person: ["M8.4 8a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0-7.2 0", "M4.5 20.5c0-4 3.4-6.5 7.5-6.5s7.5 2.5 7.5 6.5"],
    folder: ["M3 7a2 2 0 0 1 2-2h4.2l2 2.2H19a2 2 0 0 1 2 2v8.3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"],
    cloud: ["M7 18.5a4.2 4.2 0 0 1-.6-8.36 5.6 5.6 0 0 1 10.9 1.1A3.7 3.7 0 0 1 17 18.5Z"],
    bell: ["M6 16.5V11a6 6 0 0 1 12 0v5.5l1.6 2H4.4Z", "M10 20.5a2 2 0 0 0 4 0"],
    globe: ["M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M3 12h18", "M12 3c3.2 3.2 3.2 14.8 0 18", "M12 3c-3.2 3.2-3.2 14.8 0 18"],
    star: ["M12 3.2l2.7 5.6 6.1.8-4.5 4.3 1.1 6.1L12 17.1 6.6 20l1.1-6.1L3.2 9.6l6.1-.8Z"],
    lock: ["M6.5 11h11a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-7A1.5 1.5 0 0 1 6.5 11Z", "M8 11V8a4 4 0 0 1 8 0v3"],
    play: ["M8 5.5v13l11-6.5Z"],
    moon: ["M20 14.5A8.5 8.5 0 1 1 9.5 4a7 7 0 0 0 10.5 10.5Z"],
    spark: ["M12 2c.6 5.2 4.8 9.4 10 10-5.2.6-9.4 4.8-10 10-.6-5.2-4.8-9.4-10-10 5.2-.6 9.4-4.8 10-10Z"],
    eye: ["M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12Z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0"],
    horn: ["M4 10v4a1 1 0 0 0 1 1h2l8 4.5v-15L7 9H5a1 1 0 0 0-1 1Z", "M18.5 9.2a4 4 0 0 1 0 5.6"],
    code: ["M8.5 7l-5 5 5 5", "M15.5 7l5 5-5 5"],
    card: ["M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z", "M3 10h18", "M6.5 15h4"],
    up: ["M12 19V5", "M6 11l6-6 6 6"],
    check: ["M5 12.5l4.5 4.5L19 7.5"],
    x: ["M6 6l12 12", "M18 6L6 18"],
    crop: ["M4 5h16v14H4Z", "M4 15l4.5-4.5 3.5 3.5 2.5-2.5L20 17"],
  };
  // An icon centred on the origin, `size` across.
  F.icon = (parent, name, o = {}) => {
    const size = o.size || 48, k = size / 24;
    const g = F.g(parent);
    const inner = F.g(g, {
      transform: `scale(${k}) translate(-12 -12)`, fill: o.fill || "none", stroke: o.stroke || C.ink,
      "stroke-width": (o.sw || 2) , "stroke-linecap": "round", "stroke-linejoin": "round",
    });
    (I[name] || []).forEach((d) => F.wob(F.el("path", { d }, inner), { amp: 0.9 / k, wl: 26 / k, step: 1.6 / k }));
    return g;
  };
})();
