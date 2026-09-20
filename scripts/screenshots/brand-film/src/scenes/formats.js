// What the phone shows for each kind of file. Every builder draws into the content box under
// the app bar and returns update(p), where p is seconds since that file was opened. They are
// abstractions of Gander's real screens: sheet tabs along the top in the accent red, the
// page pill at the foot of a PDF, the recents rows with their square badges.
(function () {
  const F = window.F, C = F.C, E = F.E;
  const BOX = (F.BOX = { x: -197, y: -299, w: 394, h: 716 });
  const R = (p, x, y, w, h, fill, rx = 0, extra) => F.el("rect", Object.assign({ x, y, width: Math.max(0, w), height: h, fill, rx }, extra), p);
  const bgRect = (g, fill) => R(g, BOX.x - 4, BOX.y - 2, BOX.w + 8, BOX.h + 8, fill);
  F.content = {};

  // ---- a landscape, used as the photo, the video, and the figure on a PDF page -------------
  // 400 x 300. The goose on the lake is drawn at full size and scaled right down, so that a
  // camera can go all the way into it and still find a drawing there.
  F.landscape = (parent, pal = {}) => {
    const g = F.g(parent);
    R(g, 0, 0, 400, 300, pal.sky || "#CFE3EE");
    const sun = F.el("circle", { cx: 300, cy: 84, r: 27, fill: pal.sun || C.coral }, g);
    const clouds = F.g(g, { fill: pal.cloud || "#F7F3EA" });
    [[60, 60, 1], [210, 38, 0.7], [330, 140, 0.55]].forEach(([x, y, s]) =>
      F.el("path", { d: "M0,0 a14,14 0 0 1 26,-8 a18,18 0 0 1 34,2 a12,12 0 0 1 14,12 a9,9 0 0 1 -6,14 h-66 a11,11 0 0 1 -2,-20 Z", transform: `translate(${x} ${y}) scale(${s})` }, clouds));
    F.el("path", { d: "M-10,210 L70,96 L118,150 L176,70 L250,168 L300,120 L410,215 V300 H-10 Z", fill: pal.far || "#9CB2C6" }, g);
    F.el("path", { d: "M176,70 L200,102 L186,98 L174,110 L160,94 Z M70,96 L88,116 L72,112 L58,118 Z", fill: "#F7F3EA" }, g);
    F.el("path", { d: "M-10,225 C60,180 120,190 190,214 C250,180 330,176 410,206 V300 H-10 Z", fill: pal.mid || "#7FA686" }, g);
    F.el("path", { d: "M-10,246 C80,228 300,226 410,244 V300 H-10 Z", fill: pal.lake || "#A9CFE0" }, g);
    F.el("path", { d: "M-10,282 C100,262 250,272 410,268 V300 H-10 Z", fill: pal.near || "#5E8C6A" }, g);
    [[36, 232, 1], [58, 238, 0.8], [352, 222, 1.1], [372, 230, 0.75]].forEach(([x, y, s]) =>
      F.el("path", { d: "M0,0 l-9,22 h18 Z M0,-12 l-7,16 h14 Z", fill: pal.tree || "#3F6B55", transform: `translate(${x} ${y}) scale(${s})` }, g));
    const bird = F.g(g);
    F.T(bird, 168, 252, 0, 0.055);
    F.swimmer(bird);
    return { root: g, sun, clouds, bird, at: [168, 252] };
  };
  // A Canada goose on the water, 260 units long, facing left. Its head is the icon's.
  F.swimmer = (parent) => {
    const g = F.g(parent);
    F.el("path", { d: "M-170,34 h110 M-40,52 h150 M60,34 h120", stroke: "#F7F3EA", "stroke-width": 7, "stroke-linecap": "round", fill: "none", opacity: 0.8 }, g);
    F.el("path", { d: "M-78,20 C-84,-30 -30,-58 40,-52 C90,-48 128,-30 150,-44 C146,-10 120,24 60,28 C10,32 -50,34 -78,20 Z", fill: "#8A7866" }, g);
    F.el("path", { d: "M-78,20 C-84,-14 -62,-40 -34,-50 C-46,-20 -40,10 -20,29 C-44,29 -64,27 -78,20 Z", fill: "#DDD3C2" }, g);
    F.el("path", { d: "M96,-40 C116,-34 134,-34 150,-44 C148,-24 138,-6 122,8 C120,-10 110,-28 96,-40 Z", fill: C.cheek }, g);
    F.el("path", { d: "M128,-38 C138,-38 146,-40 152,-46 C150,-32 146,-22 140,-14 Z", fill: C.goose }, g);
    F.el("path", { d: "M-10,-46 C30,-30 80,-30 118,-38", stroke: "#75644F", "stroke-width": 5, fill: "none", "stroke-linecap": "round" }, g);
    const neck = F.g(g);
    F.T(neck, -50, -130, 0, 5.4);
    F.el("path", { d: "M3.15,0 L3.15,20 C3.4,24 1,26 -3.15,26 L-3.15,0 Z", fill: C.goose }, neck);
    F.el("path", { d: "M3.15,2 L3.15,-4.4 C3.15,-8.8 2.55,-11.8 1.05,-13.3 C-0.15,-14.5 -1.75,-15.15 -3.45,-15.1 C-6.25,-15 -8.45,-13.1 -9.05,-10.4 L-14.75,-8.6 C-15.45,-8.35 -15.45,-7.65 -14.75,-7.45 L-8.95,-6.1 C-8.25,-4.3 -6.65,-3.1 -4.75,-2.8 C-3.65,-2.6 -3.15,-1.7 -3.15,0 L-3.15,2 Z", fill: C.goose }, neck);
    F.el("ellipse", { cx: -4.95, cy: -6.5, rx: 3.5, ry: 2.4, fill: C.cheek, transform: "rotate(-22 -4.95 -6.5)" }, neck);
    F.el("circle", { cx: -5.75, cy: -11.2, r: 1.1, fill: C.cream }, neck);
    return g;
  };

  // ---- XLS -----------------------------------------------------------------------------------
  F.content.XLS = (parent) => {
    const g = F.g(parent), x0 = BOX.x, y0 = BOX.y;
    bgRect(g, "#FFFFFF");
    const tabs = ["Summary", "Depots", "Fleet", "Capex"], tx = [16, 126, 222, 300], tw = [100, 86, 68, 78];
    const pill = R(g, 0, y0 + 11, 10, 36, C.red, 18);
    const tabText = tabs.map((s, i) => F.text(g, s, { x: x0 + tx[i] + tw[i] / 2, y: y0 + 36, size: 19, weight: 600, anchor: "middle" }));
    R(g, x0, y0 + 58, BOX.w, 36, "#EEF3EA");
    const gut = 40, cw = (BOX.w - gut) / 4, rh = 46, top = y0 + 94;
    ["A", "B", "C", "D"].forEach((s, i) => F.text(g, s, { x: x0 + gut + cw * (i + 0.5), y: y0 + 83, size: 17, weight: 600, fill: C.inkFaint, anchor: "middle" }));
    const grid = F.g(g, { stroke: "#E3E8E1", "stroke-width": 1.5 });
    for (let i = 0; i <= 4; i++) F.el("line", { x1: x0 + gut + cw * i, y1: y0 + 58, x2: x0 + gut + cw * i, y2: BOX.y + BOX.h }, grid);
    const ra = F.rng(3), rb = F.rng(9), rows = [];
    for (let r = 0; r < 14; r++) {
      const row = F.g(g), y = top + r * rh;
      F.el("line", { x1: x0, y1: y + rh, x2: x0 + BOX.w, y2: y + rh, stroke: "#E3E8E1", "stroke-width": 1.5 }, row);
      F.text(row, String(r + 1), { x: x0 + gut / 2, y: y + 30, size: 15, weight: 500, fill: C.inkFaint, anchor: "middle" });
      const head = r === 0, cells = [];
      for (let c = 0; c < 4; c++) {
        const wa = (head ? 0.62 : c === 0 ? 0.5 + ra() * 0.4 : 0.28 + ra() * 0.4) * cw, wb = (head ? 0.55 : c === 0 ? 0.45 + rb() * 0.45 : 0.25 + rb() * 0.45) * cw;
        const tintCell = !head && c > 0 && ra() < 0.16;
        if (tintCell) R(row, x0 + gut + cw * c + 1, y + 1, cw - 2, rh - 2, "#DDEBD8");
        const bar = R(row, 0, y + 17, 10, 11, head ? C.ink : c === 0 ? "#9AA3AD" : "#C3CAD2", 5.5);
        cells.push({ bar, wa, wb, c, left: x0 + gut + cw * c + 9, right: x0 + gut + cw * (c + 1) - 9 });
      }
      rows.push({ row, cells, y });
    }
    const sel = R(g, 0, 0, cw, rh, "rgba(46,125,50,0.10)", 3, { stroke: C.fmt.XLS, "stroke-width": 3.5 });
    const cell = (r, c) => [x0 + gut + cw * c, top + r * rh];
    return {
      root: g,
      update(p) {
        const sw = E.io(F.prog(p, 1.0, 1.3));
        pill.setAttribute("x", x0 + F.lerp(tx[0], tx[1], sw));
        pill.setAttribute("width", F.lerp(tw[0], tw[1], sw));
        tabText.forEach((n, i) => n.setAttribute("fill", i === 0 ? F.mixHex("#FFFFFF", C.inkSoft, sw) : i === 1 ? F.mixHex(C.inkSoft, "#FFFFFF", sw) : C.inkSoft));
        rows.forEach(({ row, cells }, r) => {
          const a = F.tw(p, 0.04 + r * 0.035, 0.35, E.out), k = F.tw(p, 1.05 + r * 0.025, 0.3, E.io);
          F.op(row, a);
          row.setAttribute("transform", `translate(0 ${(1 - a) * 14})`);
          cells.forEach((cl) => {
            const w = F.lerp(cl.wa, cl.wb, k) * (1 - 0.5 * Math.sin(k * Math.PI));
            cl.bar.setAttribute("width", w);
            cl.bar.setAttribute("x", cl.c === 0 ? cl.left : cl.right - w);
          });
        });
        const a = cell(2, 1), b = cell(5, 2), c = cell(8, 1);
        const m1 = E.io(F.prog(p, 0.55, 0.8)), m2 = E.io(F.prog(p, 1.4, 1.65));
        sel.setAttribute("x", F.lerp(F.lerp(a[0], b[0], m1), c[0], m2));
        sel.setAttribute("y", F.lerp(F.lerp(a[1], b[1], m1), c[1], m2));
        F.op(sel, F.tw(p, 0.3, 0.2, E.lin));
      },
    };
  };

  // ---- PDF -----------------------------------------------------------------------------------
  const pdfPage = (parent, y, kind, night) => {
    const g = F.g(parent), w = 358, h = 500, x = -w / 2;
    F.T(g, 0, y);
    R(g, x + 3, 4, w, h, "rgba(26,34,44,0.10)", 3);
    const paper = R(g, x, 0, w, h, "#FFFFFF", 3);
    const bars = [];
    const bar = (bx, by, bw, bh, fill, keep) => { const n = R(g, x + bx, by, bw, bh, fill, bh / 2); if (!keep) bars.push(n); return n; };
    if (kind === 0) {
      bar(84, 34, 190, 13, C.ink); bar(114, 56, 130, 7, "#9AA3AD");
      bar(24, 88, 112, 9, "#2F5FA8", true);
      [310, 300, 310, 286, 170].forEach((bw, i) => bar(24, 110 + i * 14, bw, 6, "#C3CAD2"));
      bar(24, 190, 150, 9, "#2F5FA8", true);
      const fig = F.g(g);
      F.T(fig, x + 24, 212, 0, 0.775);
      F.landscape(fig);
      bar(70, 456, 218, 5, "#C3CAD2");
    } else {
      bar(24, 34, 130, 9, "#2F5FA8", true);
      [310, 296, 310, 304, 280, 310, 210].forEach((bw, i) => bar(24, 58 + i * 14, bw, 6, "#C3CAD2"));
      bar(24, 176, 160, 9, "#2F5FA8", true);
      [310, 288, 310, 300, 120].forEach((bw, i) => bar(24, 200 + i * 14, bw, 6, "#C3CAD2"));
      for (let r = 0; r < 4; r++) for (let c = 0; c < 3; c++) R(g, x + 24 + c * 104, 292 + r * 34, 98, 28, r === 0 ? "#DCE3EC" : "#EEF1F5", 3);
      [310, 300, 240].forEach((bw, i) => bar(24, 444 + i * 14, bw, 6, "#C3CAD2"));
    }
    return { root: g, paper, bars };
  };
  F.pdfPage = pdfPage;
  F.content.PDF = (parent) => {
    const g = F.g(parent);
    bgRect(g, "#E7E1D6");
    const pages = F.g(g);
    pdfPage(pages, BOX.y + 18, 0); pdfPage(pages, BOX.y + 18 + 518, 1); pdfPage(pages, BOX.y + 18 + 1036, 1);
    const pill = F.g(g);
    F.T(pill, 0, BOX.y + BOX.h - 60);
    F.el("path", { d: F.rr(96, 42, 21), fill: "rgba(26,34,44,0.88)" }, pill);
    const num = F.text(pill, "1 / 6", { y: 7, size: 20, weight: 600, fill: C.onDark, anchor: "middle" });
    return {
      root: g,
      update(p) {
        const s = E.io(F.prog(p, 0.6, 1.35));
        pages.setAttribute("transform", `translate(0 ${-518 * s + (1 - F.tw(p, 0, 0.5, E.out)) * 30})`);
        num.textContent = s > 0.55 ? "2 / 6" : "1 / 6";
      },
    };
  };

  // ---- DOC -----------------------------------------------------------------------------------
  F.content.DOC = (parent) => {
    const g = F.g(parent), x = BOX.x + 30, y = BOX.y;
    bgRect(g, "#FFFFFF");
    const lines = [];
    const ln = (lx, ly, w, h, fill) => lines.push({ n: R(g, x + lx, y + ly, w, h, fill, h / 2), w });
    ln(0, 40, 250, 20, C.fmt.DOC); ln(0, 72, 170, 20, C.fmt.DOC); ln(0, 112, 120, 8, "#9AA3AD");
    [334, 320, 334, 300, 196].forEach((w, i) => ln(0, 152 + i * 20, w, 9, "#C3CAD2"));
    ln(0, 274, 140, 12, C.ink);
    [0, 1, 2].forEach((i) => { F.el("circle", { cx: x + 8, cy: y + 310 + i * 26, r: 4.5, fill: C.fmt.DOC }, g); ln(26, 305 + i * 26, [260, 290, 210][i], 9, "#C3CAD2"); });
    [334, 310, 334, 250].forEach((w, i) => ln(0, 400 + i * 20, w, 9, "#C3CAD2"));
    const tbl = F.g(g);
    for (let r = 0; r < 4; r++) for (let c = 0; c < 3; c++) R(tbl, x + c * 113, y + 500 + r * 40, 107, 34, r === 0 ? "#D5E3F3" : "#EEF2F7", 4);
    return {
      root: g,
      update(p) {
        lines.forEach(({ n, w }, i) => n.setAttribute("width", w * F.tw(p, 0.05 + i * 0.04, 0.4, E.out)));
        F.op(tbl, F.tw(p, 0.75, 0.35, E.lin));
      },
    };
  };

  // ---- PPT -----------------------------------------------------------------------------------
  F.content.PPT = (parent) => {
    const g = F.g(parent), w = 358, h = 201;
    bgRect(g, "#EFE9DD");
    const clipId = F.id("sl");
    F.el("rect", { x: -w / 2, y: 0, width: w, height: h, rx: 8 }, F.el("clipPath", { id: clipId }, g));
    const slides = [0, 1, 2].map((i) => {
      const s = F.g(g), inner = F.g(s, { "clip-path": `url(#${clipId})` });
      R(s, -w / 2 + 3, 4, w, h, "rgba(26,34,44,0.10)", 8);
      s.insertBefore(s.lastChild, inner);
      R(inner, -w / 2, 0, w, h, i === 0 ? C.fmt.PPT : "#FFFFFF");
      return { s, inner, y: BOX.y + 18 + i * (h + 16) };
    });
    const [a, b, c] = slides.map((s) => s.inner);
    F.el("circle", { cx: 130, cy: 150, r: 92, fill: "#D8761F" }, a);
    F.el("circle", { cx: 150, cy: 40, r: 34, fill: "#F3C9A5" }, a);
    R(a, -150, 62, 190, 20, "#FFF6EA", 10); R(a, -150, 94, 130, 20, "#FFF6EA", 10); R(a, -150, 136, 90, 8, "#F3C9A5", 4);
    R(b, -150, 26, 150, 12, C.ink, 6);
    F.el("line", { x1: -150, y1: 172, x2: 150, y2: 172, stroke: "#C3CAD2", "stroke-width": 2 }, b);
    const bars = [70, 104, 58, 124, 92].map((bh, i) => ({ n: R(b, -132 + i * 58, 172 - bh, 36, bh, i === 3 ? C.fmt.PPT : "#F0C9A0", 5), bh }));
    R(c, -150, 26, 170, 12, C.ink, 6);
    const donut = F.el("circle", { cx: -84, cy: 118, r: 46, fill: "none", stroke: C.fmt.PPT, "stroke-width": 24, "stroke-dasharray": "0 400", transform: "rotate(-90 -84 118)" }, c);
    F.el("circle", { cx: -84, cy: 118, r: 46, fill: "none", stroke: "#F3DFC9", "stroke-width": 24 }, c);
    c.appendChild(donut);
    [130, 150, 104].forEach((bw, i) => { F.el("circle", { cx: 0, cy: 92 + i * 28, r: 5, fill: i === 0 ? C.fmt.PPT : "#F0C9A0" }, c); R(c, 16, 87 + i * 28, bw, 9, "#C3CAD2", 4.5); });
    return {
      root: g,
      update(p) {
        slides.forEach(({ s, y }, i) => {
          const k = F.tw(p, 0.03 + i * 0.11, 0.55, E.out);
          F.T(s, (1 - k) * 430, y);
        });
        bars.forEach(({ n, bh }, i) => { const k = F.tw(p, 0.45 + i * 0.06, 0.5, E.back(1.4)); n.setAttribute("height", bh * k); n.setAttribute("y", 172 - bh * k); });
        donut.setAttribute("stroke-dasharray", `${F.tw(p, 0.6, 0.7, E.io) * 190} 400`);
      },
    };
  };

  // ---- IMG -----------------------------------------------------------------------------------
  F.content.IMG = (parent) => {
    const g = F.g(parent);
    bgRect(g, "#100F0D");
    const ph = F.g(g), land = F.landscape(ph);
    return {
      root: g, land,
      update(p) {
        const a = F.tw(p, 0, 0.5, E.out), z = F.tw(p, 0.5, 0.75, E.io);
        const s = F.lerp(0.985, 2.3, z) * F.lerp(0.9, 1, a), fx = 176, fy = 96; // zooms on the summit
        F.T(ph, -s * F.lerp(200, fx, z), 40 - s * F.lerp(150, fy, z), 0, s);
        F.op(ph, F.tw(p, 0, 0.25, E.lin));
      },
    };
  };

  // ---- VID -----------------------------------------------------------------------------------
  F.content.VID = (parent) => {
    const g = F.g(parent);
    bgRect(g, "#100F0D");
    const clipId = F.id("vd");
    F.el("rect", { x: -197, y: -130, width: 394, height: 222 }, F.el("clipPath", { id: clipId }, g));
    const fr = F.g(g, { "clip-path": `url(#${clipId})` }), ph = F.g(fr);
    F.T(ph, -197, -170, 0, 0.985);
    const land = F.landscape(ph, { sky: "#F6C8A0", sun: "#FFF1D6", far: "#B07A8C", mid: "#7B5D7A", lake: "#F2B48C", near: "#4A3F5C", tree: "#3A3148", cloud: "#FBE3CC" });
    const flock = F.g(fr, { stroke: "#3A3148", "stroke-width": 3, fill: "none", "stroke-linecap": "round", "stroke-linejoin": "round" });
    const birds = [[0, 0], [26, 14], [-24, 16], [52, 30], [-50, 32]].map(([bx, by]) => ({ n: F.el("path", null, flock), bx, by }));
    const ctl = F.g(g);
    R(ctl, -165, 160, 330, 6, "#3B3832", 3);
    const fill = R(ctl, -165, 160, 10, 6, C.coral, 3), knob = F.el("circle", { cy: 163, r: 10, fill: C.coral }, ctl);
    const tl = F.text(ctl, "0:07", { x: -165, y: 200, size: 18, weight: 500, fill: C.muted });
    F.text(ctl, "1:24", { x: 165, y: 200, size: 18, weight: 500, fill: C.muted, anchor: "end" });
    F.el("circle", { cx: 0, cy: 270, r: 38, fill: "rgba(248,239,224,0.12)" }, ctl);
    R(ctl, -13, 254, 9, 32, C.onDark, 3); R(ctl, 4, 254, 9, 32, C.onDark, 3);
    return {
      root: g,
      update(p) {
        const k = 0.085 + p * 0.03;
        fill.setAttribute("width", 330 * k); knob.setAttribute("cx", -165 + 330 * k);
        tl.textContent = "0:0" + Math.min(9, 7 + Math.floor(p * 1.6));
        land.clouds.setAttribute("transform", `translate(${p * 22} 0)`);
        land.sun.setAttribute("cy", 84 + p * 9);
        const flap = Math.sin(p * 13);
        birds.forEach(({ n, bx, by }) => n.setAttribute("d", `M${bx - 9},${by + flap * 5} Q${bx - 4},${by - 3} ${bx},${by} Q${bx + 4},${by - 3} ${bx + 9},${by + flap * 5}`));
        F.T(flock, 120 - p * 60, -60 - p * 8);
        F.op(g, F.tw(p, 0, 0.25, E.lin));
      },
    };
  };

  // ---- AUD -----------------------------------------------------------------------------------
  F.content.AUD = (parent) => {
    const g = F.g(parent);
    bgRect(g, C.surface);
    const tile = F.g(g);
    F.el("path", { d: F.rr(250, 250, 40), fill: F.tint(C.fmt.AUD, 0.34) }, tile);
    const note = F.g(tile, { fill: C.fmt.AUD });
    F.el("ellipse", { cx: -38, cy: 52, rx: 27, ry: 20, transform: "rotate(-18 -38 52)" }, note);
    F.el("ellipse", { cx: 52, cy: 34, rx: 27, ry: 20, transform: "rotate(-18 52 34)" }, note);
    F.el("path", { d: "M-20,52 V-52 L78,-72 V34 H66 V-38 L-8,-23 V52 Z" }, note);
    const n = 29, bars = Array.from({ length: n }, (_, i) => R(g, -165 + i * (330 / n) + 2, 0, 330 / n - 5, 10, C.fmt.AUD, 3));
    R(g, -165, 300, 330, 6, C.skelWarm, 3);
    const fill = R(g, -165, 300, 10, 6, C.fmt.AUD, 3), knob = F.el("circle", { cy: 303, r: 10, fill: C.fmt.AUD }, g);
    F.text(g, "0:42", { x: -165, y: 340, size: 18, weight: 500, fill: C.inkFaint });
    F.text(g, "3:05", { x: 165, y: 340, size: 18, weight: 500, fill: C.inkFaint, anchor: "end" });
    return {
      root: g,
      update(p) {
        const beat = Math.pow(Math.abs(Math.sin(p * Math.PI * 2)), 6); // two to the second, to go with the cut
        F.T(tile, 0, -110, Math.sin(p * 3) * 1.5, F.tw(p, 0, 0.5, E.spring(0.5, 11)) * (1 + beat * 0.035));
        bars.forEach((b, i) => {
          const env = Math.sin(((i + 0.5) / n) * Math.PI);
          const h = 8 + (70 * Math.abs(Math.sin(i * 0.9 + p * 9)) * 0.55 + 70 * Math.abs(Math.sin(i * 0.37 - p * 5.3)) * 0.45) * env * F.tw(p, 0.1, 0.4, E.out);
          b.setAttribute("height", h); b.setAttribute("y", 200 - h / 2);
        });
        const k = 0.22 + p * 0.02;
        fill.setAttribute("width", 330 * k); knob.setAttribute("cx", -165 + 330 * k);
      },
    };
  };

  // ---- MD ------------------------------------------------------------------------------------
  F.content.MD = (parent) => {
    const g = F.g(parent), x = BOX.x + 28, y = BOX.y;
    bgRect(g, C.surface);
    const rawId = F.id("mr"), outId = F.id("mo");
    const rawClip = F.el("rect", { x: BOX.x - 4, width: BOX.w + 8 }, F.el("clipPath", { id: rawId }, g));
    const outClip = F.el("rect", { x: BOX.x - 4, y: BOX.y - 4, width: BOX.w + 8 }, F.el("clipPath", { id: outId }, g));
    const raw = F.g(g, { "clip-path": `url(#${rawId})` }), out = F.g(g, { "clip-path": `url(#${outId})` });
    // As typed. The marks are the point, so they get the accent.
    const rawLine = (ly, parts) => {
      const t = F.text(raw, "", { x, y: y + ly, size: 23, weight: 400, fill: C.inkSoft });
      parts.forEach(([s, mark]) => { const sp = F.el("tspan", { fill: mark ? C.coral : null, "font-weight": mark ? 600 : null }, t); sp.textContent = s; });
    };
    rawLine(64, [["# ", 1], ["Field notes"]]);
    rawLine(132, [["Two "], ["**", 1], ["geese"], ["**", 1], [" on the lake"]]);
    rawLine(164, [["at first light."]]);
    rawLine(232, [["- [x] ", 1], ["Count them"]]);
    rawLine(268, [["- [ ] ", 1], ["Count them again"]]);
    rawLine(336, [["> ", 1], ["They were looking"]]);
    rawLine(368, [["> ", 1], ["at me too."]]);
    // As Gander shows it.
    F.text(out, "Field notes", { x, y: y + 76, size: 44, weight: 700, fill: C.ink, ls: "-0.02em" });
    R(out, x, y + 96, 338, 2.5, C.skelWarm);
    const para = F.text(out, "", { x, y: y + 144, size: 23, weight: 400, fill: C.ink });
    [["Two "], ["geese", 1], [" on the lake"]].forEach(([s, b]) => { const sp = F.el("tspan", { "font-weight": b ? 700 : null }, para); sp.textContent = s; });
    F.text(out, "at first light.", { x, y: y + 176, size: 23, weight: 400, fill: C.ink });
    [[1, "Count them"], [0, "Count them again"]].forEach(([on, s], i) => {
      const cy = y + 232 + i * 40;
      F.el("rect", { x, y: cy - 20, width: 26, height: 26, rx: 7, fill: on ? C.red : "none", stroke: on ? C.red : C.inkFaint, "stroke-width": 2.5 }, out);
      if (on) F.el("path", { d: `M${x + 6},${cy - 7} l5,5 l9,-10`, fill: "none", stroke: "#fff", "stroke-width": 3, "stroke-linecap": "round", "stroke-linejoin": "round" }, out);
      F.text(out, s, { x: x + 40, y: cy, size: 23, weight: 400, fill: on ? C.inkFaint : C.ink });
    });
    R(out, x, y + 312, 5, 70, C.coral, 2.5);
    F.text(out, "They were looking", { x: x + 24, y: y + 340, size: 23, weight: 500, italic: true, fill: C.inkSoft });
    F.text(out, "at me too.", { x: x + 24, y: y + 372, size: 23, weight: 500, italic: true, fill: C.inkSoft });
    const wipe = R(g, BOX.x, 0, BOX.w, 4, C.coral);
    return {
      root: g,
      update(p) {
        const k = E.io(F.prog(p, 0.4, 1.0)), wy = BOX.y + k * (BOX.h + 8);
        outClip.setAttribute("height", Math.max(0, wy - BOX.y + 4));
        rawClip.setAttribute("y", wy); rawClip.setAttribute("height", BOX.h + 12);
        wipe.setAttribute("y", wy - 2);
        F.op(wipe, k > 0 && k < 1 ? 1 : 0);
        F.op(raw, F.tw(p, 0, 0.3, E.lin));
      },
    };
  };

  // ---- a list of files: the inside of a zip, and the home screen's recents -----------------
  const fileRows = (g, rows, top) =>
    rows.map(([kind, name, meta], i) => {
      const row = F.g(g), cy = top + i * 88;
      const b = F.badge(row, { label: kind, color: kind === "DIR" ? C.fmt.ZIP : C.fmt[kind], size: 58 });
      F.T(b.root, BOX.x + 52, cy);
      F.text(row, name, { x: BOX.x + 100, y: cy - 2, size: 23, weight: 500, fill: C.ink });
      F.text(row, meta, { x: BOX.x + 100, y: cy + 24, size: 17, weight: 400, fill: C.inkFaint });
      return row;
    });
  F.content.ZIP = (parent) => {
    const g = F.g(parent);
    bgRect(g, C.surface);
    F.text(g, "6 items, nothing unzipped", { x: BOX.x + 24, y: BOX.y + 46, size: 19, weight: 600, fill: C.red });
    const rows = fileRows(g, [
      ["DIR", "drawings", "Folder"], ["PDF", "Tenancy agreement.pdf", "240 KB"], ["IMG", "North elevation.jpg", "3.1 MB"],
      ["XLS", "Q3 budget.xlsx", "88 KB"], ["MD", "Notes.md", "2 KB"], ["VID", "Walkthrough.mp4", "41 MB"],
    ], BOX.y + 110);
    return {
      root: g,
      update(p) {
        rows.forEach((r, i) => { const k = F.tw(p, 0.06 + i * 0.065, 0.45, E.out); r.setAttribute("transform", `translate(0 ${(1 - k) * 46})`); F.op(r, k); });
      },
    };
  };
  F.content.HOME = (parent) => {
    const g = F.g(parent);
    bgRect(g, C.surface);
    F.text(g, "Recent files", { x: BOX.x + 24, y: BOX.y + 46, size: 20, weight: 600, fill: C.red });
    const rows = fileRows(g, [
      ["PPT", "Willowmere kickoff.pptx", "Just now"], ["DOC", "Field survey report.docx", "2 minutes ago"], ["XLS", "Q3 budget.xlsx", "4 minutes ago"],
      ["PDF", "Tenancy agreement.pdf", "7 minutes ago"], ["AUD", "Voice memo.m4a", "Yesterday"], ["ZIP", "Site pack.zip", "Yesterday"],
    ], BOX.y + 110);
    const fab = F.g(g);
    F.el("path", { d: F.rr(168, 62, 22), fill: "#FFD9CC" }, fab);
    F.text(fab, "Open a file", { y: 8, size: 21, weight: 500, fill: C.ink, anchor: "middle" });
    return {
      root: g,
      update(p) {
        rows.forEach((r, i) => { const k = F.tw(p, 0.06 + i * 0.05, 0.45, E.out); r.setAttribute("transform", `translate(0 ${(1 - k) * 40})`); F.op(r, k); });
        F.T(fab, BOX.x + BOX.w - 108, BOX.y + BOX.h - 70, 0, F.tw(p, 0.35, 0.5, E.spring(0.5, 11)));
      },
    };
  };
})();
