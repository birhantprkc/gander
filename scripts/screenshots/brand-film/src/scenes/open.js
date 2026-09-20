// 0:00 to 0:12.7. A file arrives, a dozen dialogs get between you and it, and a goose deals
// with them. Ends on the file growing into the phone that the next scene opens on.
(function () {
  const F = window.F, C = F.C, E = F.E;

  // Where the phone and its blob live for the middle of the film. Shared, because this scene
  // has to hand over on exactly the frame the next one starts with.
  F.SET = { px: 1400, py: 624, ps: 0.95, bx: 1400, by: 606, br: 480, seed: 11 };

  const CARD = [1420, 560];
  const POPS = [
    { title: "Sign in to continue", icon: "person", col: "DOC", yes: "Sign in", at: [-150, -150, -6] },
    { title: "Allow access to contacts?", icon: "person", col: "IMG", yes: "Allow", at: [130, 40, 5] },
    { title: "Start your free trial", icon: "star", col: "PPT", yes: "Try free", at: [-190, 110, -3] },
    { title: "Allow location access?", icon: "pin", col: "XLS", yes: "Allow", at: [120, -200, 8] },
    { title: "Uploading to the cloud…", icon: "cloud", col: "AUD", yes: "OK", at: [-20, -30, -2] },
    { title: "Turn on notifications?", icon: "bell", col: "VID", yes: "Turn on", at: [-230, -40, 6] },
    { title: "Watch an ad to continue", icon: "play", col: "PDF", yes: "Watch", at: [200, 190, -7] },
    { title: "Create an account", icon: "person", col: "MD", yes: "Sign up", at: [-40, -290, 3] },
    { title: "Rate this app", icon: "star", col: "PPT", yes: "Rate", at: [-90, 250, 4] },
    { title: "Allow access to photos?", icon: "crop", col: "IMG", yes: "Allow", at: [250, -60, -4] },
    { title: "Upgrade to Premium", icon: "card", col: "ZIP", yes: "Upgrade", at: [20, 130, 2] },
    { title: "Allow microphone access?", icon: "mic", col: "AUD", yes: "Allow", at: [-60, -90, -5] },
  ];
  // They start on the beat and then stop waiting for it.
  const POP_AT = [2.3, 2.8, 3.3, 3.7, 4.05, 4.35, 4.6, 4.8, 5.0, 5.15, 5.3, 5.45];
  const HONK = 8.4;

  F.scenes.push({
    id: "open", t0: 0, t1: 12.7,
    build(root) {
      F.ground(root, C.cream);
      const S = F.SET;
      const radii = F.blobRadii(S.seed, 9, 0.16), radii0 = F.blobRadii(5, 9, 0.2);
      const blob = F.el("path", { fill: F.tint(C.fmt.XLS, 0.3) }, root);

      const label = F.text(root, "Q3 budget.xlsx", { x: CARD[0], y: CARD[1] + 262, size: 32, weight: 500, fill: C.inkSoft, anchor: "middle" });
      const ring = F.el("circle", { cx: CARD[0], cy: CARD[1], r: 10, fill: "none", stroke: C.ink, "stroke-width": 5 }, root);
      const card = F.fileCard(root, { w: 300, badge: "XLS", color: C.fmt.XLS });

      const pops = POPS.map((p) => {
        const n = F.popup(root, { w: 470, h: 200, title: p.title, icon: p.icon, yes: p.yes, tint: F.tint(C.fmt[p.col], 0.34), accent: C.fmt[p.col] });
        const t = n.root.querySelector("text");
        for (let s = 29; t.getComputedTextLength() > 330 && s > 20; s--) t.setAttribute("font-size", s);
        return n;
      });

      const phone = F.phone(root, { title: "Q3 budget.xlsx" });
      const goose = F.goose(root, { u: 12 });
      const burst = F.g(root, { stroke: C.ink, "stroke-width": 7, "stroke-linecap": "round" });
      const rays = [-52, -34, -16, 2, 20, 38, 56].map((deg) => ({ deg, n: F.el("line", null, burst) }));
      const honk = F.text(root, "HONK!", { size: 128, weight: 800, italic: true, fill: C.coral, anchor: "middle", ls: "0.01em" });

      const h1 = F.headline(root, { x: 146, y: 500, size: 124, lines: [{ text: "Someone sent", fill: C.ink }, { text: "you a file.", fill: C.red }] });
      const h2 = F.headline(root, { x: 146, y: 500, size: 116, lines: [{ text: "All you wanted", fill: C.ink }, { text: "was to open it.", fill: C.red }] });
      const h3 = F.headline(root, { x: 146, y: 500, size: 164, lines: [{ text: "Take a", fill: C.ink }, { text: "gander.", fill: C.red }] });

      const hidden = { bx: 776, by: 1300, ax: 860, ay: 1300, flip: 1 };
      const POSE = [
        [6.4, hidden],
        [7.3, { bx: 776, by: 1300, ax: 860, ay: 470, flip: 1, tilt: 4 }, E.spring(0.62, 9)],
        [7.7, { bx: 776, by: 1300, ax: 878, ay: 452, flip: 1, tilt: -8 }],
        [7.95, { bx: 776, by: 1300, ax: 878, ay: 452, flip: 1, tilt: -8 }],
        [8.34, { bx: 776, by: 1300, ax: 792, ay: 512, flip: 1, tilt: 16, bend: -70, brow: 0.8 }, E.soft],
        [8.46, { bx: 776, by: 1300, ax: 940, ay: 428, flip: 1, tilt: -5, bend: 50, brow: 1, open: 1 }, E.cubicOut],
        [8.95, { bx: 776, by: 1300, ax: 930, ay: 432, flip: 1, tilt: -3, bend: 40, brow: 1, open: 0.9 }],
        [9.25, { bx: 776, by: 1300, ax: 868, ay: 466, flip: 1, tilt: 2 }],
        [10.15, { bx: 776, by: 1300, ax: 868, ay: 466, flip: 1, tilt: 2 }],
        [10.45, { bx: 776, by: 1300, ax: 850, ay: 480, flip: 0, tilt: -6 }],
        [11.2, { bx: 776, by: 1300, ax: 850, ay: 480, flip: 0, tilt: -6 }],
        [11.5, { bx: 776, by: 1300, ax: 900, ay: 500, flip: 1, tilt: -10 }],
        [11.95, { bx: 776, by: 1300, ax: 930, ay: 520, flip: 1, tilt: -12, bend: 30 }],
        [12.5, { bx: 776, by: 1300, ax: 880, ay: 1300, flip: 1 }, E.in],
      ];

      return (t) => {
        // The blob comes in with the file, and by the end has become the next scene's blob.
        const grow = F.tw(t, 0.35, 1.0, E.out), hand = F.tw(t, 11.9, 0.8, E.io);
        blob.setAttribute("d", F.blobPath(F.mixRadii(radii0, radii, hand), F.lerp(380, S.br, hand) * grow, 0.02, t));
        F.T(blob, F.lerp(CARD[0], S.bx, hand), F.lerp(CARD[1] + 10, S.by, hand));

        // The file: dropped in, tapped, buried, uncovered, and then it grows up.
        const drop = F.tw(t, 0.3, 1.0, E.spring(0.5, 10));
        const shake = t > HONK ? 7 * Math.exp(-3.2 * (t - HONK)) * Math.sin(15 * (t - HONK)) : 0;
        const morph = F.tw(t, 12.05, 0.65, E.io);
        const cx = F.lerp(CARD[0], S.px, morph), cy = F.lerp(F.lerp(-320, CARD[1], drop), S.py, morph);
        F.T(card.root, cx, cy, F.lerp(-16, -4, drop) * (1 - morph) + shake, F.lerp(1, 1.62, morph));
        F.op(card.root, 1 - F.tw(t, 12.35, 0.3, E.lin));
        F.T(phone.root, cx, cy, -4 * (1 - morph), F.lerp(0.5, S.ps, morph));
        F.op(phone.root, F.tw(t, 12.3, 0.32, E.lin));
        F.show(phone.root, t > 12.25);
        F.op(label, F.tw(t, 1.2, 0.4, E.lin) * (1 - F.tw(t, 2.5, 0.4, E.lin)) + F.tw(t, 9.3, 0.4, E.lin) * (1 - F.tw(t, 11.9, 0.3, E.lin)));
        const rp = F.prog(t, 2.0, 2.55);
        ring.setAttribute("r", 10 + E.cubicOut(rp) * 120);
        F.op(ring, rp > 0 && rp < 1 ? 0.55 * (1 - rp) : 0);

        // Dialogs: in on a spring, a little restless while they sit, and out on the honk,
        // nearest the beak first.
        const tip = goose.beak(POSE[5][1]);
        pops.forEach((p, i) => {
          const [dx, dy, rot] = POPS[i].at, x0 = CARD[0] + dx, y0 = CARD[1] + dy;
          const a = F.tw(t, POP_AT[i], 0.55, E.spring(0.55, 12));
          const vx = x0 - tip[0], vy = y0 - tip[1] + 60, d = Math.hypot(vx, vy);
          const fly = F.tw(t, HONK + 0.02 + d * 0.00035, 0.85, E.quadOut);
          const spin = (i % 2 ? 1 : -1) * (160 + i * 23) * fly;
          F.T(p.root, x0 + (vx / d) * 2300 * fly, y0 + (1 - a) * 40 + (vy / d) * 2300 * fly - 260 * Math.sin(fly * Math.PI) * (i % 3 === 0 ? 1 : 0.3),
            rot + Math.sin(t * 2.6 + i * 1.7) * 0.7 + spin, 0.8 * F.lerp(0.35, 1, a));
          F.op(p.root, F.tw(t, POP_AT[i], 0.1, E.lin));
          F.show(p.root, t >= POP_AT[i] && fly < 1);
        });

        // The goose.
        const pose = F.poseKeys(t, POSE);
        pose.blink = F.blinks(t, [7.55, 9.5, 10.8, 11.7]);
        pose.ay += Math.sin(t * 1.7) * 3;
        pose.tilt += Math.sin(t * 0.93 + 1) * 1.4;
        goose.pose(pose);
        F.show(goose.root, t > 6.35);

        // The honk: lines out of the beak, and the word, since there is no sound to carry it.
        const hp = F.prog(t, HONK + 0.02, HONK + 0.5), bt = goose.beak(pose);
        rays.forEach(({ deg, n }, i) => {
          const r = (deg * Math.PI) / 180, r0 = 40 + E.cubicOut(hp) * 150, len = 64 * (1 - hp) + 8;
          F.set(n, { x1: bt[0] + Math.cos(r) * r0, y1: bt[1] + Math.sin(r) * r0, x2: bt[0] + Math.cos(r) * (r0 + len), y2: bt[1] + Math.sin(r) * (r0 + len) });
        });
        F.op(burst, hp > 0 && hp < 1 ? 1 - hp * hp : 0);
        const hs = F.tw(t, HONK, 0.4, E.spring(0.42, 13));
        F.T(honk, 905, 218 - 20 * hs, -9 + Math.sin(t * 40) * 1.2 * (1 - F.prog(t, HONK, HONK + 0.6)), hs);
        F.op(honk, F.tw(t, HONK, 0.06, E.lin) * (1 - F.tw(t, HONK + 0.62, 0.2, E.lin)));

        h1.update(F.prog(t, 0.9, 1.9), F.prog(t, 2.55, 3.05));
        h2.update(F.prog(t, 3.1, 4.1), F.prog(t, 5.9, 6.4));
        h3.update(F.prog(t, 9.5, 10.5), F.prog(t, 11.85, 12.4));
      };
    },
  });
})();
