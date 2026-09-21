// The props: format badge, file card, phone, dialog. All are built centred on their own
// origin so a scene places them with one transform.
(function () {
  const F = window.F, C = F.C;

  // The rounded square from the app's file rows.
  F.badge = (parent, o) => {
    const s = o.size || 96, g = F.g(parent);
    const rect = F.wob(F.el("path", { d: F.rr(s, s, s * 0.24), fill: o.color }, g), { amp: 1.3, wl: 60, step: 4 });
    const tx = F.text(g, o.label, {
      y: s * 0.115, size: s * (o.label.length > 3 ? 0.27 : 0.31), weight: 700, fill: "#fff", anchor: "middle", ls: "0.02em",
    });
    return { root: g, rect, tx };
  };

  // The icon's file card: white sheet, folded corner, badge, two grey lines.
  F.fileCard = (parent, o) => {
    const w = o.w || 260, h = o.h || w * 1.31, r = w * 0.1, fold = w * 0.3;
    const x = -w / 2, y = -h / 2, g = F.g(parent);
    if (o.shadow !== false)
      F.el("path", { d: F.rr(w, h, r), fill: "rgba(26,34,44,0.10)", transform: `translate(${w * 0.035} ${w * 0.05})` }, g);
    const sheet = F.g(g);
    F.wob(F.el("path", {
      d: `M${x + r},${y} h${w - fold - r} l${fold},${fold} v${h - fold - r} q0,${r} ${-r},${r} h${-(w - 2 * r)} q${-r},0 ${-r},${-r} v${-(h - 2 * r)} q0,${-r} ${r},${-r} Z`,
      fill: o.fill || C.paper, stroke: o.stroke || "none", "stroke-width": o.sw || 0, "stroke-linejoin": "round",
    }, sheet), { amp: 1.8, wl: 90 });
    F.el("path", { d: `M${x + w - fold - 1},${y + 1} l${fold},${fold} h${-fold * 0.78} q${-fold * 0.22},0 ${-fold * 0.22},${-fold * 0.22} Z`, fill: C.fold }, sheet);
    const bs = w * 0.36;
    let badge = null;
    if (o.badge) {
      badge = F.badge(g, { label: o.badge, color: o.color, size: bs });
      F.T(badge.root, x + w * 0.1 + bs / 2, y + w * 0.3 + bs / 2);
    }
    const lines = o.lines || [0.62, 0.46, 0.55];
    lines.forEach((k, i) =>
      F.el("rect", { x: x + w * 0.1, y: y + h * 0.63 + i * w * 0.115, width: w * k, height: w * 0.055, rx: w * 0.027, fill: C.skel }, g)
    );
    return { root: g, badge, w, h };
  };

  // A phone. The bezel is a frame drawn over the screen's contents rather than under them,
  // so the hand-drawn wobble on its inner edge hides the content's perfectly straight clip.
  F.phone = (parent, o = {}) => {
    const w = o.w || 420, h = o.h || 860, bz = 13, R = 60;
    const g = F.g(parent);
    F.el("path", { d: F.rr(w, h, R), fill: o.shadow || "rgba(26,34,44,0.13)", transform: "translate(18 24)" }, g);
    const sw = w - bz * 2, sh = h - bz * 2;
    const clipId = F.id("ph");
    const cp = F.el("clipPath", { id: clipId }, g);
    F.el("path", { d: F.rr(sw + 8, sh + 8, R - bz + 4) }, cp);
    const screen = F.g(g, { "clip-path": `url(#${clipId})` });
    const bg = F.el("rect", { x: -sw / 2 - 4, y: -sh / 2 - 4, width: sw + 8, height: sh + 8, fill: C.surface }, screen);
    const content = F.g(screen);
    // App bar: status strip plus toolbar, in the app's own peach.
    const barH = 118, top = -sh / 2;
    const bar = F.g(screen);
    const barBg = F.el("rect", { x: -sw / 2 - 4, y: top - 4, width: sw + 8, height: barH + 4, fill: C.appbar }, bar);
    const ic = (name, x) => F.T(F.icon(bar, name, { size: 34, sw: 2.2 }), x, top + 78);
    ic("back", -sw / 2 + 40);
    ic("search", sw / 2 - 92);
    ic("share", sw / 2 - 40);
    const title = F.text(bar, o.title || "", { x: -sw / 2 + 78, y: top + 88, size: 27, weight: 500, fill: C.ink });
    const frame = F.g(g);
    F.wob(F.el("path", { d: F.rr(w, h, R) + " " + F.rr(sw, sh, R - bz), fill: o.body || C.ink, "fill-rule": "evenodd" }, frame), { amp: 2.2, wl: 130, step: 6 });
    F.el("circle", { cx: 0, cy: top + 26, r: 8, fill: o.body || C.ink }, frame);
    return {
      root: g, screen, content, bar, barBg, bg, title, frame,
      w, h, sw, sh, top, barH,
      cx0: -sw / 2, cy0: top + barH, cw: sw, ch: sh - barH, // the content box under the bar
    };
  };

  // A dialog of the kind that gets between you and a file.
  F.popup = (parent, o) => {
    const w = o.w || 430, h = o.h || 210, g = F.g(parent);
    F.el("path", { d: F.rr(w, h, 30), fill: "rgba(26,34,44,0.12)", transform: "translate(10 14)" }, g);
    const face = F.g(g);
    F.wob(F.el("path", { d: F.rr(w, h, 30), fill: C.paper, stroke: C.ink, "stroke-width": 4.5, "stroke-linejoin": "round" }, face), { amp: 2, wl: 95 });
    F.el("circle", { cx: -w / 2 + 62, cy: -h / 2 + 64, r: 34, fill: o.tint }, face);
    F.T(F.icon(g, o.icon, { size: 38, sw: 2.2 }), -w / 2 + 62, -h / 2 + 64);
    F.text(g, o.title, { x: -w / 2 + 112, y: -h / 2 + 58, size: 29, weight: 600, fill: C.ink });
    F.el("rect", { x: -w / 2 + 112, y: -h / 2 + 76, width: w * 0.42, height: 11, rx: 5.5, fill: C.skelWarm }, g);
    // Two buttons: the one they want you to press, and the one you were looking for.
    const by = h / 2 - 52;
    F.el("path", { d: F.rr(150, 50, 25), fill: o.accent, transform: `translate(${w / 2 - 102} ${by})` }, g);
    F.text(g, o.yes || "Allow", { x: w / 2 - 102, y: by + 8, size: 23, weight: 600, fill: "#fff", anchor: "middle" });
    F.text(g, o.no || "Not now", { x: w / 2 - 250, y: by + 8, size: 23, weight: 500, fill: C.inkSoft, anchor: "middle" });
    return { root: g, w, h };
  };

  // A four-point spark, the film's punctuation mark.
  F.spark = (parent, o = {}) => {
    const g = F.g(parent);
    F.el("path", {
      d: "M0,-1 C0.06,-0.4 0.4,-0.06 1,0 C0.4,0.06 0.06,0.4 0,1 C-0.06,0.4 -0.4,0.06 -1,0 C-0.4,-0.06 -0.06,-0.4 0,-1 Z",
      fill: o.fill || C.coral, transform: `scale(${o.size || 20})`,
    }, g);
    return g;
  };

  // The scene's ground, and the kicker and sub lines that sit round a headline.
  F.ground = (parent, fill) => F.el("rect", { x: -20, y: -20, width: F.W + 40, height: F.H + 40, fill }, parent);
  F.kicker = (parent, str, o = {}) =>
    F.text(parent, str.toUpperCase(), Object.assign({ size: 26, weight: 600, ls: "0.22em", fill: C.inkSoft }, o));
})();
