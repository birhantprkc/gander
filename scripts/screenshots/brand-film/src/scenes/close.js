// 0:55.2 to 1:06. Morning: three plain facts in front of a rising sun, which answers the moon
// of the night before. Then the end card, where the pieces the film has been using all along
// (three file cards and a goose looking over them) turn out to be the app's icon.
(function () {
  const F = window.F, C = F.C, E = F.E;

  // ---- facts ------------------------------------------------------------------------------
  F.scenes.push({
    id: "facts", t0: 55.2, t1: 60.0, z: 1,
    build(root) {
      const T = 55.2, clipId = F.id("dawn");
      const iris = F.el("circle", { cx: 1400, cy: 1200, r: 0 }, F.el("clipPath", { id: clipId }, root));
      const g = F.g(root, { "clip-path": `url(#${clipId})` });
      F.ground(g, C.cream);
      const rays = F.g(g, { stroke: C.coral, "stroke-width": 10, "stroke-linecap": "round" });
      const ray = Array.from({ length: 13 }, (_, i) => ({ n: F.el("line", null, rays), a: Math.PI + (i / 12) * Math.PI }));
      const sun = F.g(g);
      F.wob(F.el("path", { d: "M330,0 A330,330 0 1 1 -330,0 A330,330 0 1 1 330,0 Z", fill: C.coral }, sun), { amp: 3, wl: 170, step: 8 });
      const goose = F.goose(g, { u: 13 });
      const head = F.headline(g, {
        x: 142, y: 410, size: 136, lh: 148, stagger: 0.333,
        lines: [{ text: "Tiny.", fill: C.ink }, { text: "Free.", fill: C.ink }, { text: "Open source.", fill: C.red }],
      });
      const sub = F.text(g, "MIT licensed. Every line of it is on GitHub.", { x: 148, y: 820, size: 40, weight: 500, fill: C.inkSoft });
      const beats = [T + 1.0, T + 1.8, T + 2.6];
      F.cue(T, "dawn", { dur: 1.6 }); beats.forEach((b, i) => F.cue(b, "fact", { i })); F.cue(T + 3.2, "factSub"); F.cue(T + 4.0, "sunset", { dur: 0.75 });
      const up = { bx: 1400, by: 1320, ax: 1392, ay: 470, tilt: -4 };
      const POSE = [
        [T + 0.7, { bx: 1400, by: 1320, ax: 1400, ay: 1320 }], [T + 1.7, up, E.spring(0.6, 9)],
        ...beats.slice(1).flatMap((b) => [[b + 0.1, up], [b + 0.28, Object.assign({}, up, { ay: 500, tilt: -14 }), E.soft], [b + 0.6, up, E.out]]),
        [T + 3.9, up], [T + 4.25, Object.assign({}, up, { ax: 1400, ay: 1330 }), E.in],
      ];
      return (lt, t) => {
        iris.setAttribute("r", F.tw(t, T, 1.0, E.io) * 2400);
        const rise = F.tw(t, T + 0.2, 1.6, E.out), set = F.tw(t, T + 4.0, 0.75, E.in), sy = F.lerp(1500, 760, rise) + set * 800;
        F.T(sun, 1400, sy);
        ray.forEach(({ n, a }, i) => {
          const r0 = 380 + Math.sin(t * 2 + i) * 8, len = (i % 2 ? 46 : 78) * F.tw(t, T + 1.0 + i * 0.03, 0.5, E.spring(0.5, 11));
          const aa = a + Math.sin(t * 0.5) * 0.05;
          F.set(n, { x1: 1400 + Math.cos(aa) * r0, y1: sy + Math.sin(aa) * r0, x2: 1400 + Math.cos(aa) * (r0 + len), y2: sy + Math.sin(aa) * (r0 + len) });
        });
        const pose = F.poseKeys(t, POSE);
        pose.blink = F.blinks(t, [T + 2.2, T + 3.5]);
        goose.pose(pose);
        head.update(F.prog(t, beats[0], beats[0] + 2.4), F.prog(t, T + 4.1, T + 4.7));
        const a = F.tw(t, T + 3.2, 0.5, E.out), b = F.tw(t, T + 4.15, 0.3, E.in);
        sub.setAttribute("transform", `translate(0 ${(1 - a) * 24})`);
        F.op(sub, a * (1 - b));
      };
    },
  });

  // ---- end card -----------------------------------------------------------------------------
  F.scenes.push({
    id: "end", t0: 60.0, t1: 66.0,
    build(root) {
      const T = 60.0, k = 5.7, CX = 960, CY = 318;
      const P = (x, y) => [CX + (x - 54) * k, CY + (y - 54) * k]; // launcher-icon units to stage
      F.ground(root, C.cream);
      const tile = F.g(root);
      F.el("path", { d: F.rr(72 * k, 72 * k, 17 * k), fill: "rgba(26,34,44,0.10)", transform: "translate(10 14)" }, tile);
      F.wob(F.el("path", { d: F.rr(72 * k, 72 * k, 17 * k), fill: "#EFE7D6" }, tile), { amp: 2.2, wl: 140, step: 6 });
      const goose = F.goose(root, { u: k });
      const cards = [
        { col: "#1565C0", to: [43, 61, -16] }, { col: "#2E7D32", to: [67.5, 61, 14] }, { col: "#D32F2F", to: [54, 54, -2] },
      ].map((c, i) => Object.assign(c, { n: F.fileCard(root, { w: 26 * k, badge: " ", color: c.col, lines: [0.62, 0.46], shadow: i === 2 }) }));
      const word = F.headline(root, { x: CX, y: 760, size: 196, weight: 700, ls: "-0.035em", anchor: "middle", lines: [{ text: "Gander", fill: C.ink }] });
      const tag = F.text(root, "", { x: CX, y: 862, size: 60, weight: 500, fill: C.ink, anchor: "middle" });
      [["Take a gander at "], ["any", 1], [" file."]].forEach(([s, red]) => {
        const sp = F.el("tspan", red ? { fill: C.red, "font-style": "italic", "font-weight": 600 } : null, tag);
        sp.textContent = s;
      });
      tag.setAttribute("xml:space", "preserve");
      const url = F.text(root, "arjun.maniyani.com/gander", { x: CX, y: 978, size: 34, weight: 500, fill: C.inkSoft, anchor: "middle", ls: "0.06em" });
      F.cue(T, "end"); F.cue(T + 0.15, "tile"); [0, 1, 2].forEach((i) => F.cue(T + 0.55 + i * 0.1, "fan", { i }));
      F.cue(T + 1.1, "gooseUp", { dur: 0.8 }); F.cue(T + 1.5, "wordmark"); F.cue(T + 2.2, "tagline"); F.cue(T + 2.8, "url");
      F.cue(T + 3.9, "stretch", { dur: 0.5 }); F.cue(T + 4.4, "turn"); F.cue(T + 4.95, "lastHonk"); F.cue(T + 5.2, "turn");
      const home = P(57.85, 37.4), base = P(57.85, 66);
      const at = (ay, o) => Object.assign({ bx: base[0], by: base[1], ax: home[0], ay }, o);
      const POSE = [
        [T + 1.1, at(P(0, 60)[1])], [T + 1.9, at(home[1]), E.spring(0.55, 10)], [T + 3.9, at(home[1])],
        [T + 4.4, at(P(0, 25)[1], { tilt: 4 }), E.io], [T + 4.7, at(P(0, 25)[1], { tilt: 4, flip: 1 })], [T + 5.2, at(P(0, 25)[1], { tilt: 6, flip: 1 })],
        [T + 5.45, at(P(0, 25)[1], { tilt: 2 })], [T + 5.9, at(home[1]), E.io],
      ];
      return (lt, t) => {
        F.T(tile, CX, CY, 0, F.tw(t, T + 0.15, 0.7, E.spring(0.55, 10)));
        cards.forEach((c, i) => {
          const a = F.tw(t, T + 0.55 + i * 0.1, 0.75, E.spring(0.6, 10)), from = P(54, 60), to = P(c.to[0], c.to[1]);
          F.T(c.n.root, F.lerp(from[0], to[0], a), F.lerp(from[1] + 40, to[1], a), c.to[2] * a, F.lerp(0.5, 1, a));
          F.op(c.n.root, F.tw(t, T + 0.55 + i * 0.1, 0.15, E.lin));
        });
        const pose = F.poseKeys(t, POSE);
        pose.blink = F.blinks(t, [T + 2.7, T + 3.6, T + 5.0]);
        goose.pose(pose);
        F.show(goose.root, t > T + 1.1);
        word.update(F.prog(t, T + 1.5, T + 2.4));
        [[tag, T + 2.2], [url, T + 2.8]].forEach(([n, s]) => {
          const a = F.tw(t, s, 0.6, E.out);
          n.setAttribute("transform", `translate(0 ${(1 - a) * 26})`);
          F.op(n, a);
        });
      };
    },
  });
})();
