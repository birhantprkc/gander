// The four acts that follow the format parade, all on the viewer scene's phone:
//   28.9  Finds anything.     a dictionary entry for "gander", searched for "gander"
//   33.9  Every last pixel.   a pinch, then the camera goes into the photo and does not stop
//   38.9  Reads at 2am.       the dark it ends in is the night; the page turns over
//   44.9  Takes nothing.      no permissions, no internet, none of the rest
(function () {
  const F = window.F, C = F.C, E = F.E, B = F.BOX;
  const R = (p, x, y, w, h, fill, rx = 0, extra) => F.el("rect", Object.assign({ x, y, width: Math.max(0, w), height: h, fill, rx }, extra), p);
  const bgRect = (g, fill) => R(g, B.x - 4, B.y - 2, B.w + 8, B.h + 8, fill);

  // A kicker and a two-line headline in the text column. Times are [in, out].
  const title = (set, o) => {
    const ink = o.dark ? C.onDark : C.ink, g = F.g(set.type);
    const kicks = o.kickers.map((k) => {
      const kg = F.g(g);
      F.wob(F.el("path", { d: F.rr(76, 76, 38), fill: o.dark ? "#2E261A" : k.tint, transform: "translate(184 300)" }, kg), { amp: 1.2, wl: 50, step: 4 });
      F.T(F.icon(kg, k.icon, { size: 42, sw: 2.1, stroke: ink }), 184, 300);
      F.text(kg, k.label, { x: 244, y: 314, size: 40, weight: 600, fill: ink });
      return { kg, k };
    });
    const head = F.headline(g, { x: 142, y: 500, size: 156, lh: 156, lines: [{ text: o.lines[0], fill: ink }, { text: o.lines[1], fill: o.dark ? C.coral : C.red }] });
    return (t) => {
      kicks.forEach(({ kg, k }) => {
        const a = F.tw(t, k.at[0], 0.4, E.out), b = F.tw(t, k.at[1], 0.25, E.in);
        kg.setAttribute("transform", `translate(0 ${(1 - a) * 26 - b * 26})`);
        F.op(kg, a * (1 - b));
      });
      head.update(F.prog(t, o.at[0], o.at[0] + 1), F.prog(t, o.at[1], o.at[1] + 0.5));
    };
  };

  // ==== FIND ===================================================================================
  F.acts.push((set) => {
    const { phone, front, S } = set, AT = 28.9;
    const doc = F.g(phone.content, { id: "findDoc" });
    bgRect(doc, "#FFFFFF");
    const marks = F.g(doc), x = B.x + 28, y = B.y;
    const L = (str, ly, o) => F.text(doc, str, Object.assign({ x, y: y + ly, size: 23, weight: 400, fill: C.ink }, o));
    const m1 = L("gander", 84, { size: 56, weight: 700, ls: "-0.02em" });
    L("noun", 120, { italic: true, weight: 500, fill: C.inkFaint, size: 22 });
    L("1", 178, { weight: 700, fill: C.red }); L("A male goose.", 178, { x: x + 30 });
    L("2", 216, { weight: 700, fill: C.red }); L("A look, or a glance.", 216, { x: x + 30 });
    R(doc, x, y + 250, 4, 70, C.coral, 2);
    const m2 = L("“Go on, take a gander", 278, { x: x + 22, italic: true, weight: 500, fill: C.inkSoft });
    L("at this.”", 310, { x: x + 22, italic: true, weight: 500, fill: C.inkSoft });
    L("Probably from the way a goose", 376); L("cranes its neck to look. To take", 408);
    const m3 = L("a gander is to stretch for a", 440);
    L("better view.", 472);
    [330, 300, 320, 180].forEach((w, i) => R(doc, x, y + 530 + i * 26, w, 9, "#D5DAE0", 4.5));
    const hits = [m1, m2, m3].map((tx) => {
      const i = tx.textContent.indexOf("gander"), s = parseFloat(tx.getAttribute("font-size"));
      const x0 = tx.getStartPositionOfChar(i).x - 4, x1 = tx.getEndPositionOfChar(i + 5).x + 4, by = parseFloat(tx.getAttribute("y"));
      return { n: R(marks, x0, by - s * 0.8, x1 - x0, s * 1.06, "#FFE27A", 5), cx: (x0 + x1) / 2, cy: by - s * 0.3 };
    });
    set.addFile("FIND", AT, { root: doc, update() {} }, "Glossary.docx", F.mixHex(C.cream, "#E9B93A", 0.42), 61);

    // The app bar turns into Gander's find bar: what you typed, which match, up and down.
    const bar = F.g(phone.bar);
    R(bar, -128, phone.top + 40, 330, 78, C.appbar);
    const field = F.text(bar, "", { x: -119, y: phone.top + 88, size: 27, weight: 500, fill: C.ink });
    const caret = R(bar, 0, phone.top + 62, 2.5, 32, C.red);
    const count = F.text(bar, "1 of 3", { x: 84, y: phone.top + 86, size: 20, weight: 500, fill: C.inkSoft, anchor: "end" });
    const chev = (d, cx) => F.T(F.g(bar, null), cx, phone.top + 78).appendChild(F.el("path", { d, fill: "none", stroke: C.ink, "stroke-width": 2.8, "stroke-linecap": "round", "stroke-linejoin": "round" })).parentNode;
    chev("M-9,5 l9,-9 l9,9", 118);
    const down = chev("M-9,-5 l9,9 l9,-9", 164);

    // The lens: the same document, drawn again larger through a round hole.
    const lens = F.g(front), LX = F.TALL ? 1156 : 1112, LY = 650, LR = 196, Z = 2.5; // a phone is narrower: keep the glass off its edge
    F.el("line", { x1: -142, y1: 142, x2: -236, y2: 236, stroke: C.ink, "stroke-width": 30, "stroke-linecap": "round" }, lens);
    F.el("circle", { r: LR, fill: "#FFFFFF" }, lens);
    const clipId = F.id("ln");
    F.el("circle", { r: LR - 4 }, F.el("clipPath", { id: clipId }, lens));
    const view = F.el("use", { href: "#findDoc" }, F.g(lens, { "clip-path": `url(#${clipId})` }));
    F.wob(F.el("path", { d: `M${LR},0 A${LR},${LR} 0 1 1 ${-LR},0 A${LR},${LR} 0 1 1 ${LR},0 Z`, fill: "none", stroke: C.ink, "stroke-width": 14 }, lens), { amp: 2, wl: 110, step: 6 });
    F.el("path", { d: `M${-LR * 0.72},${-LR * 0.36} A${LR * 0.8},${LR * 0.8} 0 0 1 ${-LR * 0.3},${-LR * 0.75}`, fill: "none", stroke: "#FFFFFF", "stroke-width": 9, "stroke-linecap": "round", opacity: 0.9 }, lens);

    const head = title(set, {
      lines: ["Finds", "anything."], at: [AT + 0.1, 33.35],
      kickers: [{ icon: "search", label: "Find in document", tint: F.mixHex(C.cream, "#E9B93A", 0.5), at: [AT + 0.25, 33.3] }],
    });
    F.cue(AT, "find");
    for (let i = 0; i < 6; i++) F.cue(29.75 + i * 0.09, "key");
    hits.forEach((_, i) => F.cue(30.35 + i * 0.07, "hit", { i }));
    F.cue(30.95, "lens"); F.cue(31.0, "next"); F.cue(32.3, "next"); F.cue(33.3, "lensOut");
    const rest = set.rest;
    set.pose.push([AT + 0.1, null], [AT + 0.5, rest({ tilt: -18, ay: 208 }), E.out], [30.9, null],
      [31.4, rest({ ax: 1486, ay: 194, tilt: -25, bend: 30 }), E.io], [33.0, rest({ ax: 1480, ay: 196, tilt: -27, bend: 30 })], [33.6, rest(), E.io]);

    return (t) => {
      head(t);
      const on = t >= AT - 0.1 && t < 34.4;
      F.show(bar, on); F.show(lens, on);
      if (!on) return;
      F.op(bar, F.tw(t, 29.45, 0.25, E.lin) * (1 - F.tw(t, 33.75, 0.2, E.lin)));
      const typed = F.clamp(Math.floor((t - 29.75) / 0.09), 0, 6);
      field.textContent = "gander".slice(0, typed);
      caret.setAttribute("x", -117 + field.getComputedTextLength());
      F.op(caret, Math.floor(t * 2.5) % 2 === 0 || typed < 6 ? 1 : 0);
      const cur = t < 31.0 ? 0 : t < 32.3 ? 1 : 2;
      count.textContent = `${cur + 1} of 3`;
      F.op(count, F.tw(t, 30.35, 0.2, E.lin));
      const press = Math.max(1 - F.prog(t, 31.0, 31.25), 1 - F.prog(t, 32.3, 32.55));
      F.T(down, 164, phone.top + 78 + (press < 1 ? press * 4 : 0), 0, 1 + (press < 1 ? press * 0.25 : 0));
      hits.forEach((h, i) => { F.op(h.n, F.tw(t, 30.35 + i * 0.07, 0.2, E.lin)); h.n.setAttribute("fill", i === cur ? "#F6A58E" : "#FFE27A"); });
      const k = E.io(F.prog(t, 32.3, 32.8)), fx = F.lerp(hits[1].cx, hits[2].cx, k), fy = F.lerp(hits[1].cy, hits[2].cy, k);
      view.setAttribute("transform", `scale(${Z * S.ps}) translate(${-fx} ${-fy})`);
      const s = F.tw(t, 30.95, 0.6, E.spring(0.5, 11)) * (1 - F.tw(t, 33.3, 0.3, E.in));
      F.T(lens, LX, LY + Math.sin(t * 1.4) * 5, Math.sin(t * 1.1) * 1.5, s);
      F.show(lens, s > 0.001);
    };
  });

  // ==== ZOOM ===================================================================================
  F.acts.push((set) => {
    const { phone, S, root, camera, type } = set, AT = 33.9, PUSH = 36.5, DARK = 38.45;
    const g = F.g(phone.content);
    bgRect(g, "#100F0D");
    const ph = F.g(g), land = F.landscape(ph);
    // The spot on the swimming goose's head that the camera ends up inside, in photo units.
    const FX = 168 + (-50 + 0.3 * 5.4) * 0.055, FY = 252 + (-130 - 9.5 * 5.4) * 0.055;
    const touch = [0, 1].map(() => F.el("circle", { r: 27, fill: "rgba(255,255,255,0.22)", stroke: "#FFFFFF", "stroke-width": 4 }, g));
    const focal = (t) => {
      const p1 = E.io(F.prog(t, 35.0, 36.0)), p2 = F.prog(t, PUSH, DARK + 0.1);
      const z = Math.exp(F.lerp(0, Math.log(5), p1) + (Math.log(900) - Math.log(5)) * E.cubicIn(p2) * (p2 > 0 ? 1 : 0));
      const m = F.clamp(p1 * 0.6 + E.io(p2) * 0.4);
      return { z, fx: F.lerp((FX - 200) * 0.985, 0, m), fy: F.lerp(40 + (FY - 150) * 0.985, 59, m), p1, p2 };
    };
    set.addFile("ZOOM", AT, {
      root: g,
      update(p) {
        const t = p + AT, f = focal(t), s = 0.985 * f.z;
        F.T(ph, f.fx - s * FX, f.fy - s * FY, 0, s);
        const d = F.lerp(34, 150, f.p1), o = F.tw(t, 34.85, 0.2, E.lin) * (1 - F.tw(t, 36.0, 0.25, E.lin));
        touch.forEach((c, i) => { const sg = i ? 1 : -1; c.setAttribute("cx", f.fx + sg * d * 0.62); c.setAttribute("cy", f.fy - sg * d * 0.78); F.op(c, o); });
      },
    }, "IMG_2041.jpg", F.tint(C.fmt.IMG, 0.3), 77);
    F.cue(AT, "photo"); F.cue(35.0, "pinch", { dur: 1.0 }); F.cue(PUSH, "push", { dur: DARK - PUSH }); F.cue(DARK, "dark");
    const veil = F.ground(root, C.dark);
    root.insertBefore(veil, type);
    set.veil = veil;
    const head = title(set, {
      lines: ["Every", "last pixel."], at: [AT + 0.25, 36.15],
      kickers: [{ icon: "crop", label: "Deep zoom into huge photos", tint: F.tint(C.fmt.IMG, 0.34), at: [AT + 0.4, 36.1] }],
    });
    set.pose.push([34.8, null], [35.3, set.rest({ tilt: -24, ay: 198, ax: 1548 }), E.io], [38.9, set.rest({ tilt: -24, ay: 198, ax: 1548 })]);
    return (t) => {
      head(t);
      // The camera follows the photo in: the world grows about the point being looked at
      // until the phone's screen is the frame, and the photo keeps going after that.
      if (t >= PUSH && t < 38.9) {
        const f = focal(t), k = Math.exp(Math.log(5.6) * E.io(F.prog(t, PUSH, 37.9))), e = E.io(F.prog(t, PUSH, 37.6));
        const [px, py] = F.toStage(S.px + f.fx * S.ps, S.py + f.fy * S.ps);
        camera.setAttribute("transform", `translate(${F.lerp(px, F.W / 2, e)} ${F.lerp(py, F.H / 2, e)}) scale(${k}) translate(${-px} ${-py})`);
      } else camera.removeAttribute("transform");
      F.op(veil, F.tw(t, DARK - 0.4, 0.4, E.lin) * (1 - F.tw(t, 39.0, 0.55, E.lin)));
      F.show(veil, t > DARK - 0.45 && t < 39.6);
    };
  });

  // ==== NIGHT ==================================================================================
  F.acts.push((set) => {
    const { phone, S, sky, behind, ground } = set, AT = 38.9, FLIP = 41.3;
    const g = F.g(phone.content);
    const dayBg = bgRect(g, "#E7E1D6");
    const pages = F.g(g);
    F.pdfPage(pages, B.y + 18, 0); F.pdfPage(pages, B.y + 18 + 518, 1);
    const clipId = F.id("nt"), clip = R(F.el("clipPath", { id: clipId }, g), B.x - 4, B.y - 4, B.w + 8, 0);
    const night = F.g(g, { "clip-path": `url(#${clipId})` });
    bgRect(night, "#141311");
    const npages = F.g(night);
    // The same two pages turned over: paper black, text white, the blue still blue, and the
    // photograph left exactly as it was printed.
    [F.pdfPage(npages, B.y + 18, 0), F.pdfPage(npages, B.y + 18 + 518, 1)].forEach((pg) => {
      pg.paper.setAttribute("fill", "#000000");
      pg.bars.forEach((b) => b.setAttribute("fill", b.getAttribute("fill") === C.ink ? "#FFFFFF" : "#D9D9D6"));
      pg.root.querySelectorAll("rect").forEach((n) => {
        const f = n.getAttribute("fill");
        if (f === "#2F5FA8") n.setAttribute("fill", "#8DB3F0");
        if (f === "#DCE3EC") n.setAttribute("fill", "#30343B");
        if (f === "#EEF1F5") n.setAttribute("fill", "#1E2126");
      });
    });
    const wipe = R(g, B.x, 0, B.w, 4, C.coral);
    const pill = F.g(g);
    F.T(pill, 0, B.y + B.h - 60);
    F.el("path", { d: F.rr(96, 42, 21), fill: "rgba(60,60,60,0.92)" }, pill);
    F.text(pill, "1 / 6", { y: 7, size: 20, weight: 600, fill: C.onDark, anchor: "middle" });
    set.addFile("NIGHT", AT, {
      root: g,
      update(p) {
        const t = p + AT, k = E.io(F.prog(t, FLIP, FLIP + 0.75)), wy = B.y + k * (B.h + 8);
        clip.setAttribute("height", Math.max(0, wy - B.y + 4));
        wipe.setAttribute("y", wy - 2); F.op(wipe, k > 0 && k < 1 ? 1 : 0);
        const sc = -E.io(F.prog(t, 42.6, 44.6)) * 150;
        pages.setAttribute("transform", `translate(0 ${sc})`); npages.setAttribute("transform", `translate(0 ${sc})`);
      },
    }, "Willowmere Phase 3.pdf", "#221C12", 88);

    // Moon, with the goose in front of it, and a few stars.
    const moon = F.g(sky);
    F.wob(F.el("path", { d: "M250,0 A250,250 0 1 1 -250,0 A250,250 0 1 1 250,0 Z", fill: "#F3E6C0" }, moon), { amp: 3, wl: 160, step: 8 });
    [[-90, -70, 46], [70, 40, 30], [-30, 110, 22], [120, -110, 18]].forEach(([cx, cy, r]) => F.el("circle", { cx, cy, r, fill: "#E7D7A6" }, moon));
    // Stars are scattered over the stage of whichever cut this is, kept off the phone, the type
    // and the moon, and only then put into the illustration's coordinates, where the sky lives.
    const MOON_Y = F.TALL ? 262 : 236, moonAt = F.toStage(1560, MOON_Y);
    const clear = F.TALL
      ? (x, y) => !(x > 300 && x < 780 && y > 770) && !(x < 800 && y > 220 && y < 860)
      : (x, y) => !(x > 1150 && x < 1660 && y > 180) && !(x < 1000 && y > 230 && y < 880);
    const rs = F.rng(21), stars = Array.from({ length: F.TALL ? 40 : 34 }, (_, i) => {
      const n = F.spark(sky, { size: 1, fill: i % 5 === 0 ? C.coral : C.onDark });
      const sx = F.TALL ? 40 + rs() * 1000 : 60 + rs() * 1800, sy = F.TALL ? 60 + rs() * (i % 3 ? 900 : 1780) : 40 + rs() * (i % 3 ? 420 : 960);
      const [x, y] = F.toWorld(sx, sy);
      return { n, x, y, keep: clear(sx, sy) && Math.hypot(sx - moonAt[0], sy - moonAt[1]) > 300, s: 9 + rs() * 17, ph: rs() * 6 };
    }).filter((s) => s.keep || (s.n.remove(), false));
    // Glare: the white page at 2am, as short rays off the phone's edges.
    const glare = F.g(behind, { stroke: "#F6E3A1", "stroke-width": 8, "stroke-linecap": "round" });
    const hw = (phone.w / 2) * S.ps, hh = (phone.h / 2) * S.ps, rays = [];
    for (let i = 0; i < 22; i++) {
      const a = (i / 22) * Math.PI * 2 + 0.14, dx = Math.cos(a), dy = Math.sin(a), k = Math.min(hw / Math.abs(dx || 1e-6), hh / Math.abs(dy || 1e-6));
      rays.push({ n: F.el("line", null, glare), x: S.px + dx * k, y: S.py + dy * k, dx, dy, i });
    }
    F.cue(AT, "night"); F.cue(39.3, "glare", { dur: FLIP - 39.3 }); F.cue(FLIP, "nightMode", { dur: 0.75 }); F.cue(42.9, "content");
    const head = title(set, {
      dark: true, lines: ["Reads", "at 2am."], at: [39.35, 44.3],
      kickers: [{ icon: "moon", label: "Night mode for PDFs", at: [39.5, 44.25] }],
    });
    set.pose.push([39.0, set.rest({ tilt: 2, ay: 196, ax: 1572 })], [FLIP + 0.3, null], [FLIP + 0.9, set.rest({ tilt: -16 }), E.io], [44.9, set.rest({ tilt: -16 })]);

    return (t, pose) => {
      head(t);
      const dark = t >= 38.6;
      ground.setAttribute("fill", dark ? C.dark : C.cream);
      phone.frame.firstChild.setAttribute("fill", dark ? "#3A3F47" : C.ink);
      F.show(sky, dark); F.show(glare, dark && t < FLIP + 1);
      if (!dark) return;
      const rise = F.tw(t, 39.0, 1.4, E.out);
      F.T(moon, 1560, F.lerp(330, MOON_Y, rise) + (t > 44.9 ? (t - 44.9) * 3 : 0));
      F.op(moon, F.tw(t, 39.0, 0.5, E.lin));
      stars.forEach((s, i) => { F.T(s.n, s.x, s.y, 0, s.s * (0.72 + 0.28 * Math.sin(t * 2.2 + s.ph)) * F.tw(t, 39.2 + i * 0.04, 0.5, E.spring(0.5, 11))); });
      const gl = F.tw(t, 39.3, 0.5, E.out) * (1 - F.tw(t, FLIP + 0.1, 0.6, E.io));
      rays.forEach((r) => {
        const r0 = 22 + Math.sin(t * 5 + r.i * 1.9) * 5, len = (30 + (r.i % 2) * 20 + Math.sin(t * 4 + r.i) * 6) * gl;
        F.set(r.n, { x1: r.x + r.dx * r0, y1: r.y + r.dy * r0, x2: r.x + r.dx * (r0 + len), y2: r.y + r.dy * (r0 + len) });
      });
      F.op(glare, gl);
      if (t < FLIP + 1) { pose.blink = Math.max(pose.blink || 0, 0.74 * gl); pose.brow = 0.35 * gl; }
      if (t > 42.9 && t < 43.5) pose.blink = Math.max(pose.blink, Math.sin(F.prog(t, 42.9, 43.5) * Math.PI)); // one slow, contented one
    };
  });

  // ==== NOTHING ================================================================================
  F.acts.push((set) => {
    const { phone, S, behind, type } = set, AT = 44.9, NET = 49.3, REST = 52.5, SURF = "#1B1813";
    const rowText = (g, s, ly, o) => F.text(g, s, Object.assign({ x: B.x + 28, y: B.y + ly, size: 24, weight: 500, fill: C.onDark }, o));

    // 1. Android's own page for the app, which for Gander has nothing on it.
    const perms = F.g(phone.content);
    bgRect(perms, SURF);
    const tile = F.g(perms);
    F.T(tile, B.x + 66, B.y + 84);
    F.el("path", { d: F.rr(76, 76, 20), fill: C.cream }, tile);
    F.el("path", { d: "M3.15,5 L3.15,-4.4 C3.15,-8.8 2.55,-11.8 1.05,-13.3 C-0.15,-14.5 -1.75,-15.15 -3.45,-15.1 C-6.25,-15 -8.45,-13.1 -9.05,-10.4 L-14.75,-8.6 C-15.45,-8.35 -15.45,-7.65 -14.75,-7.45 L-8.95,-6.1 C-8.25,-4.3 -6.65,-3.1 -4.75,-2.8 C-3.65,-2.6 -3.15,-1.7 -3.15,0 L-3.15,5 Z", fill: C.goose, transform: "translate(14 22) scale(2.6)" }, tile);
    F.el("ellipse", { cx: -4.95, cy: -6.5, rx: 3.5, ry: 2.4, fill: C.cheek, transform: "translate(14 22) scale(2.6) rotate(-22 -4.95 -6.5)" }, tile);
    rowText(perms, "Gander", 94, { x: B.x + 122, size: 32, weight: 600 });
    rowText(perms, "Permissions", 184, { size: 20, weight: 600, fill: C.coral });
    const panel = F.g(perms);
    F.T(panel, 0, B.y + 350);
    F.el("path", { d: F.rr(B.w - 40, 270, 26), fill: "#26221B" }, panel);
    F.el("circle", { cy: -52, r: 40, fill: "none", stroke: C.coral, "stroke-width": 5 }, panel);
    F.el("path", { d: "M-17,-51 l12,12 l23,-26", fill: "none", stroke: C.coral, "stroke-width": 6, "stroke-linecap": "round", "stroke-linejoin": "round" }, panel);
    const none = F.text(panel, "No permissions requested", { y: 48, size: 27, weight: 500, fill: C.onDark, anchor: "middle" });
    F.text(panel, "This app has asked for nothing.", { y: 86, size: 19, weight: 400, fill: C.muted, anchor: "middle" });
    [220, 180].forEach((w, i) => R(perms, B.x + 28, B.y + 560 + i * 30, w, 10, "#2F2A21", 5));
    set.addFile("PERMS", AT, { root: perms, update(p) { F.T(panel, 0, B.y + 350, 0, F.lerp(0.9, 1, F.tw(p, 0.35, 0.6, E.spring(0.5, 11)))); F.op(panel, F.tw(p, 0.35, 0.3, E.lin)); } }, "App info", "#221C12", 91);

    const bubbles = [["camera", 1078, 318], ["person", 1040, 556], ["pin", 1084, 806], ["mic", 1722, 404], ["folder", 1762, 640], ["bell", 1716, 872]].map(([icon, x, y], i) => {
      const g = F.g(behind);
      F.wob(F.el("path", { d: F.rr(156, 156, 78), fill: "#3A3022" }, g), { amp: 1.8, wl: 80, step: 5 });
      F.T(F.icon(g, icon, { size: 80, sw: 1.8, stroke: C.onDark }), 0, 0);
      const strike = F.el("line", { x1: -62, y1: 62, x2: -62, y2: 62, stroke: C.coral, "stroke-width": 11, "stroke-linecap": "round" }, g);
      return { g, strike, x, y, in: 45.55 + i * 0.11, out: 46.9 + i / 3 }; // six across one bar of the score, as triplets
    });

    // 2. No way out: files that cannot get to the cloud.
    const net = F.g(phone.content);
    bgRect(net, SURF);
    const cards = [["PDF", -96, 250, -7], ["IMG", 12, 330, 5], ["XLS", 104, 236, 9]].map(([k, cx, cy, rot]) => {
      const c = F.fileCard(net, { w: 116, badge: k, color: C.fmt[k], shadow: false });
      return { c, cx, cy: B.y + cy + 120, rot };
    });
    set.addFile("NONET", NET, { root: net, update() {} }, "Gander", "#221C12", 95);
    // Beside the phone on a phone: above it is where the two lines under the headline have to go.
    const CLOUD = F.TALL ? [1040, 430] : [1064, 236], CUT = F.TALL ? [1124, 584] : [1190, 196];
    const cloud = F.g(behind);
    F.T(F.icon(cloud, "cloud", { size: 240, sw: 1.4, stroke: C.onDark }), 0, 0);
    const link = F.el("path", { d: F.TALL ? "M1204,600 C1170,600 1100,580 1070,530" : "M1236,262 C1204,190 1170,172 1128,204", fill: "none", stroke: C.muted, "stroke-width": 6, "stroke-linecap": "round", "stroke-dasharray": "2 20" }, behind);
    const cut = F.g(behind);
    F.el("path", { d: "M-32,-32 L32,32 M32,-32 L-32,32", stroke: C.coral, "stroke-width": 14, "stroke-linecap": "round", fill: "none" }, cut);
    const sub = F.g(type);
    // Two lines in the wide cut. On a phone each is broken in two and kept to the left, clear of
    // the goose and the moon that the illustration puts to the right of them.
    const said = F.TALL
      ? [["No internet access,", "by design."], ["Not a promise.", "A missing capability."]]
      : [["No internet access, by design."], ["Not a promise. A missing capability."]];
    const [s1, s2] = said.map((rows, i) => {
      const g = F.g(sub);
      rows.forEach((str, j) => F.text(g, str, { x: 146, y: (F.TALL ? 760 + i * 114 : 770 + i * 58) + j * 50, size: F.TALL ? 40 : 42, weight: 500, fill: i ? C.coral : C.onDark }));
      return g;
    });

    // 3. And none of the rest.
    const rest = F.g(phone.content);
    bgRect(rest, SURF);
    const checks = ["No ads", "No trackers", "No analytics", "No accounts"].map((s, i) => {
      const g = F.g(rest), cy = B.y + 130 + i * 152;
      F.el("circle", { cx: B.x + 74, cy, r: 34, fill: C.coral }, g);
      F.el("path", { d: `M${B.x + 58},${cy + 1} l11,11 l22,-24`, fill: "none", stroke: SURF, "stroke-width": 7, "stroke-linecap": "round", "stroke-linejoin": "round" }, g);
      F.text(g, s, { x: B.x + 134, y: cy + 14, size: 40, weight: 600, fill: C.onDark });
      return { g, at: REST + 0.4 + i * 0.5 }; // one to a beat
    });
    set.addFile("CHECKS", REST, { root: rest, update() {} }, "Gander", "#221C12", 97);

    const head = title(set, {
      dark: true, lines: ["Takes", "nothing."], at: [AT + 0.15, 55.0],
      kickers: [
        { icon: "lock", label: "Zero permissions", at: [AT + 0.3, NET - 0.25] },
        { icon: "globe", label: "No internet access", at: [NET + 0.1, REST - 0.25] },
        { icon: "check", label: "None of the rest, either", at: [REST + 0.1, 54.95] },
      ],
    });
    F.cue(AT, "nothing");
    bubbles.forEach((b, i) => { F.cue(b.in, "bubble", { i }); F.cue(b.out, "strike", { i }); });
    F.cue(NET, "noNet"); F.cue(NET + 0.35, "cloud");
    cards.forEach((_, i) => [49.95, 50.75].forEach((at) => F.cue(at + i * 0.13 + 0.34, "bump", { i })));
    F.cue(50.85, "cut"); F.cue(51.35, "cloudOff", { dur: 0.9 }); F.cue(REST, "rest");
    checks.forEach((c, i) => F.cue(c.at, "check", { i }));
    // Night has reached the system too: the app bar goes dark with the page under it.
    const barInk = phone.bar.querySelectorAll("g[stroke]");
    const r = set.rest;
    set.pose.push(
      [46.7, null], [47.0, r({ tilt: -20, ay: 198, ax: 1540, bend: 20 }), E.io], [47.8, r({ tilt: -27, ay: 194, ax: 1530, bend: 30 })],
      [48.1, r({ tilt: -10, ay: 200, flip: 1 })], [48.95, r({ tilt: -24, ay: 196, flip: 1 })], [49.25, r()],
      [49.8, r({ tilt: 9, ay: 214, ax: 1540 }), E.io], [51.3, r({ tilt: 12, ay: 214, ax: 1530 })], [52.2, r(), E.io],
      ...checks.flatMap((c) => [[c.at - 0.05, null], [c.at + 0.12, r({ ay: 212, tilt: -21 }), E.soft], [c.at + 0.36, r(), E.out]]),
      [55.9, r()]
    );

    return (t, pose) => {
      head(t);
      const dk = F.tw(t, AT + 0.05, 0.4, E.io);
      phone.barBg.setAttribute("fill", F.mixHex(C.appbar, "#2A241B", dk));
      phone.title.setAttribute("fill", F.mixHex(C.ink, C.onDark, dk));
      barInk.forEach((n) => n.setAttribute("stroke", F.mixHex(C.ink, C.onDark, dk)));
      bubbles.forEach((b) => {
        const a = F.tw(t, b.in, 0.55, E.spring(0.5, 12)), st = F.tw(t, b.out, 0.14, E.out), gone = F.tw(t, b.out + 0.16, 0.22, E.in);
        F.T(b.g, b.x, b.y + Math.sin(t * 1.5 + b.x) * 5, 0, a * (1 - gone) * (1 + 0.12 * Math.sin(st * Math.PI)));
        F.set(b.strike, { x2: -62 + 124 * st, y2: 62 - 124 * st });
        F.op(b.strike, st > 0 ? 1 : 0);
        F.show(b.g, t >= b.in && gone < 1);
      });
      // Each card has two goes at leaving through the top of the screen and is turned back.
      cards.forEach(({ c, cx, cy, rot }, i) => {
        let y = cy, sq = 1;
        [49.95 + i * 0.13, 50.75 + i * 0.13].forEach((at) => {
          const up = E.cubicIn(F.prog(t, at, at + 0.34)), back = F.tw(t, at + 0.34, 0.5, E.spring(0.42, 12));
          if (t >= at && t < at + 0.9) { y = F.lerp(cy, B.y + 92, up) + (cy - (B.y + 92)) * back * (up >= 1 ? 1 : 0); sq = 1 - 0.18 * Math.sin(F.prog(t, at + 0.3, at + 0.46) * Math.PI); }
        });
        F.T(c.root, cx, y + Math.sin(t * 1.8 + i * 2) * 4, rot + Math.sin(t * 1.3 + i) * 2, 1, sq);
      });
      const con = F.tw(t, NET + 0.35, 0.6, E.spring(0.55, 10)), off = F.tw(t, 51.35, 0.9, E.in);
      F.T(cloud, CLOUD[0] - off * 420, CLOUD[1] - off * 60 + Math.sin(t * 1.2) * 6, 0, con);
      F.op(cloud, 1 - off);
      F.show(cloud, t > NET + 0.3 && off < 1);
      link.setAttribute("stroke-dashoffset", -t * 40);
      F.op(link, F.tw(t, NET + 0.7, 0.3, E.lin) * (1 - F.tw(t, 50.95, 0.3, E.lin)));
      const cs = F.tw(t, 50.85, 0.45, E.spring(0.42, 13)) * (1 - F.tw(t, 51.5, 0.3, E.in));
      F.T(cut, CUT[0], CUT[1], 0, cs);
      F.show(cut, cs > 0.001);
      [s1, s2].forEach((n, i) => {
        const a = F.tw(t, NET + 0.5 + i * 1.25, 0.5, E.out), b = F.tw(t, REST - 0.35, 0.3, E.in);
        n.setAttribute("transform", `translate(0 ${(1 - a) * 24})`);
        F.op(n, a * (1 - b));
      });
      checks.forEach((c) => {
        const a = F.tw(t, c.at, 0.5, E.spring(0.55, 11));
        c.g.setAttribute("transform", `translate(${(1 - a) * 60} 0)`);
        F.op(c.g, F.tw(t, c.at, 0.15, E.lin));
      });
      if (t > 51.7 && t < 52.3) pose.blink = Math.max(pose.blink || 0, Math.sin(F.prog(t, 51.7, 52.3) * Math.PI));
    };
  });
})();
