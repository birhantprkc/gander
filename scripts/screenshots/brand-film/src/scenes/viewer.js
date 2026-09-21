// 0:12.7 to 0:55.9. One phone, in one place, for the whole middle of the film, while what is
// on it, what is behind it and what the goose makes of it all change. The acts that follow
// the format parade (find, zoom, night, nothing) live in acts.js and hang off the same set.
(function () {
  const F = window.F, C = F.C, E = F.E;
  const T0 = 12.7, T1 = 55.9;

  // [kind, opens at, what the kicker calls it, the file's name]
  const SLOTS = [
    // Four beats, then three at a time, then four: every cut is on a beat of the score.
    ["XLS", 12.9, "Spreadsheets", "Q3 budget.xlsx"],
    ["PDF", 14.9, "PDF documents", "Tenancy agreement.pdf"],
    ["DOC", 16.4, "Word documents", "Field survey report.docx"],
    ["PPT", 17.9, "Slides", "Willowmere kickoff.pptx"],
    ["IMG", 19.4, "Photos", "IMG_2041.jpg"],
    ["VID", 20.9, "Video", "Walkthrough.mp4"],
    ["AUD", 22.4, "Audio", "Voice memo.m4a"],
    ["MD", 23.9, "Markdown", "Notes.md"],
    ["ZIP", 25.4, "Zip archives", "Site pack.zip"],
    ["HOME", 26.9, "And practically anything else", "Gander"],
  ];
  const FORMATS_END = (F.FORMATS_END = 28.9);
  const CHIPS = [
    [".csv", "XLS"], [".heic", "IMG"], [".mkv", "VID"], [".flac", "AUD"], [".ods", "XLS"], [".svg", "IMG"], [".webm", "VID"], [".json", "TXT"],
    [".webp", "IMG"], [".ogg", "AUD"], [".xlsb", "XLS"], [".mov", "VID"], [".avif", "IMG"], [".xml", "TXT"], [".opus", "AUD"], [".log", "TXT"],
  ];

  F.acts = [];
  F.scenes.push({
    id: "viewer", t0: T0, t1: T1,
    build(root) {
      const S = F.SET;
      const ground = F.ground(root, C.cream);
      const world = F.g(root);
      const sky = F.g(world), blobLayer = F.g(world), behind = F.g(world);
      const goose = F.goose(world, { u: 11 });
      const phone = F.phone(world, { title: "" });
      F.T(phone.root, S.px, S.py, 0, S.ps);
      const front = F.g(world), type = F.g(root);
      const set = { root, ground, world, sky, blobLayer, behind, goose, phone, front, type, S, T0, T1, pose: [], titles: [] };

      // ---- blob: one shape and colour per file, morphing between them ------------------------
      const blob = F.el("path", null, blobLayer);
      const shapes = SLOTS.map(([k], i) => ({
        radii: i === 0 ? F.blobRadii(S.seed, 9, 0.16) : F.blobRadii(20 + i * 7, 9, 0.17),
        fill: k === "HOME" ? F.tint(C.coral, 0.42) : F.tint(C.fmt[k], 0.3),
      }));
      set.blob = blob;

      // ---- the files ------------------------------------------------------------------------
      const fit = (str, max) => {
        phone.title.textContent = str;
        let s = str;
        while (phone.title.getComputedTextLength() > max && s.length > 4) { s = s.slice(0, -1); phone.title.textContent = s.trimEnd() + "…"; }
        return phone.title.textContent;
      };
      const files = SLOTS.map(([k, at, , name]) => ({ k, at, c: F.content[k](phone.content), title: fit(name, 206) }));
      set.setTitle = (s) => { if (phone.title.textContent !== s) phone.title.textContent = s; };
      // An act opens a file of its own by adding it here, with the blob it wants behind it.
      set.addFile = (k, at, c, name, fill, seed) => {
        files.push({ k, at, c, title: fit(name, 206) });
        shapes.push({ radii: F.blobRadii(seed, 9, 0.17), fill });
      };

      // ---- kicker and headline ---------------------------------------------------------------
      const kick = SLOTS.map(([k, at, label]) => {
        const g = F.g(type);
        const b = F.badge(g, { label: k === "HOME" ? "ETC" : k, color: k === "HOME" ? C.fmt.TXT : C.fmt[k], size: 76 });
        F.T(b.root, 184, 300);
        F.text(g, label, { x: 244, y: 314, size: 40, weight: 600, fill: C.ink });
        return g;
      });
      const head = F.headline(type, { x: 142, y: 500, size: 156, lh: 156, lines: [{ text: "Opens", fill: C.ink }, { text: "everything.", fill: C.red }] });
      // Three small sparks off the badge each time a new kind of file opens.
      const pips = [[-58, -50, 15], [-72, -8, 9], [-30, -72, 8]].map(([dx, dy, size]) => ({ n: F.spark(type, { size: 1 }), dx, dy, size }));

      // ---- the chips that come out at the end of the parade ----------------------------------
      const HOME_AT = SLOTS[9][1], AUD_AT = SLOTS[6][1];
      const r = F.rng(4);
      const chips = CHIPS.map(([ext, fam], i) => {
        const g = F.g(behind);
        const tx = F.text(g, ext, { size: 27, weight: 600, fill: C.ink });
        const w = tx.getComputedTextLength() + 70;
        tx.setAttribute("x", -w / 2 + 48); tx.setAttribute("y", 9);
        F.wob(F.el("path", { d: F.rr(w, 58, 29), fill: C.paper, stroke: C.ink, "stroke-width": 3.5 }, g), { amp: 1.4, wl: 50, step: 4 });
        F.el("circle", { cx: -w / 2 + 28, cy: 0, r: 9, fill: C.fmt[fam] }, g);
        g.appendChild(tx);
        const left = i % 2 === 0, row = Math.floor(i / 2);
        return { g, x: (left ? 1082 : 1748) + (r() - 0.5) * 90, y: 258 + row * 92 + (r() - 0.5) * 22 + (left ? 0 : 40), rot: (r() - 0.5) * 14, at: HOME_AT + 0.25 + i * 0.05 };
      });

      // ---- the goose's track through the parade ----------------------------------------------
      const rest = (o) => Object.assign({ bx: 1548, by: 830, ax: 1560, ay: 200, tilt: -14 }, o);
      set.rest = rest;
      set.pose.push([T0 + 0.2, rest({ ay: 660, tilt: 0 })], [T0 + 1.1, rest(), E.spring(0.55, 10)]);
      SLOTS.slice(1, 9).forEach(([k, at], i) => {
        const settle = k === "IMG" ? rest({ tilt: 4, ay: 190 }) : k === "ZIP" ? rest({ tilt: -22, ay: 200, ax: 1552 }) : rest({ tilt: [-14, -17, -11][i % 3] });
        set.pose.push([at - 0.08, null], [at + 0.13, rest({ ay: 214, tilt: -21 }), E.soft], [at + 0.5, settle, E.out]);
      });
      set.pose.push(
        [HOME_AT + 0.05, null], [HOME_AT + 0.45, rest({ ay: 214, tilt: 12 }), E.out], [HOME_AT + 0.9, rest({ ay: 212, tilt: 8 })],
        [HOME_AT + 1.15, rest({ ay: 210, tilt: 4, flip: 1 })], [HOME_AT + 1.6, rest({ ay: 212, tilt: 8, flip: 1 })], [HOME_AT + 1.9, rest()]
      );
      F.cue(T0, "phone");
      SLOTS.forEach(([kind, at]) => F.cue(at, "file", { kind }));
      chips.forEach((c) => F.cue(c.at, "chip"));
      F.cue(HOME_AT + 1.15, "turn"); F.cue(HOME_AT + 1.75, "turn");

      const acts = F.acts.map((a) => a(set));
      // A null pose means "wherever the track had got to": it holds the key before it.
      set.pose.sort((a, b) => a[0] - b[0]);
      set.pose.forEach((k, i) => { if (!k[1]) k[1] = set.pose[i - 1][1]; });

      return (lt, t) => {
        // Which file is open, and how far through the push to the next one.
        let i = 0;
        while (i < files.length - 1 && t >= files[i + 1].at) i++;
        files.forEach((f, j) => {
          const k = j === 0 ? 1 : E.std(F.prog(t, f.at, f.at + 0.45));
          const kn = j < files.length - 1 ? E.std(F.prog(t, files[j + 1].at, files[j + 1].at + 0.45)) : 0;
          const on = k > 0 && kn < 1;
          F.show(f.c.root, on);
          if (!on) return;
          f.c.root.setAttribute("transform", `translate(${(1 - k) * 402 - kn * 402} 0)`);
          f.c.update(t - f.at);
        });
        set.setTitle(files[i].title);

        {
          const a = shapes[Math.max(0, i - 1)], b = shapes[i], k = i === 0 ? 1 : E.io(F.prog(t, files[i].at - 0.1, files[i].at + 0.55));
          blob.setAttribute("d", F.blobPath(F.mixRadii(a.radii, b.radii, k), S.br, 0.02, t));
          blob.setAttribute("fill", F.mixHex(a.fill, b.fill, k));
          F.T(blob, S.bx, S.by);
        }

        kick.forEach((g, j) => {
          const at = SLOTS[j][1], next = j < SLOTS.length - 1 ? SLOTS[j + 1][1] : FORMATS_END - 0.3;
          const a = F.tw(t, j === 0 ? T0 + 0.35 : at + 0.06, 0.4, E.out), b = F.tw(t, next - 0.2, 0.22, E.in);
          g.setAttribute("transform", `translate(0 ${(1 - a) * 26 - b * 26})`);
          F.op(g, a * (1 - b));
        });
        head.update(F.prog(t, T0 + 0.25, T0 + 1.25), F.prog(t, FORMATS_END - 0.45, FORMATS_END + 0.05));
        const since = t - SLOTS[Math.min(i, SLOTS.length - 1)][1], live = i > 0 && i < SLOTS.length && t < FORMATS_END;
        pips.forEach((p, j) => {
          const k = F.prog(since, 0.1 + j * 0.05, 0.62 + j * 0.05), sc = Math.sin(k * Math.PI);
          F.T(p.n, 184 + p.dx * (0.7 + 0.5 * E.out(k)), 300 + p.dy * (0.7 + 0.5 * E.out(k)), k * 90, live ? p.size * sc : 0);
          p.n.firstChild.setAttribute("fill", j === 1 ? C.ink : C.coral);
        });

        chips.forEach((c, j) => {
          const k = F.tw(t, c.at, 0.7, E.out), sc = F.tw(t, c.at, 0.6, E.spring(0.5, 12)), out = F.tw(t, FORMATS_END - 0.4 + j * 0.012, 0.3, E.in);
          const x = F.lerp(S.px, c.x, k), y = F.lerp(S.py - 60, c.y, k) - 150 * Math.sin(k * Math.PI) * (1 - k * 0.5) + Math.sin(t * 1.7 + j) * 4 + out * 60;
          F.T(c.g, x, y, c.rot * k + (1 - k) * 80 * (j % 2 ? 1 : -1), F.lerp(0.2, 1, sc) * (1 - out * 0.3));
          F.op(c.g, F.tw(t, c.at, 0.12, E.lin) * (1 - out));
          F.show(c.g, t >= c.at && out < 1);
        });

        const pose = F.poseKeys(t, set.pose);
        if (t > AUD_AT && t < AUD_AT + 1.5) { // nodding along, twice a second, which is the beat
          const n = Math.sin((t - AUD_AT) * Math.PI * 4) * F.tw(t, AUD_AT + 0.15, 0.3, E.lin) * (1 - F.tw(t, AUD_AT + 1.15, 0.25, E.lin));
          pose.tilt += n * 7; pose.ay += Math.abs(n) * 10;
        }
        pose.blink = Math.max(pose.blink || 0, F.blinks(t, [14.2, 16.3, 17.9, 19.6, 21.0, 24.6, 26.2, 27.5, 30.2, 32.6, 36.2, 40.4, 43.6, 46.8, 49.5, 52.6, 54.8]));
        // Never quite still: it breathes, and its head drifts a little as it reads.
        pose.ay += Math.sin(t * 1.7) * 2.6;
        pose.tilt += Math.sin(t * 0.93 + 1) * 1.3;
        set.poseNow = pose;
        acts.forEach((a) => a(t, pose));
        goose.pose(pose);
      };
    },
  });
})();
