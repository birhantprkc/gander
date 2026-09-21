// The film's engine. Everything on screen is a pure function of the time it is asked for:
// no CSS animation, no wall clock, no state carried from one frame to the next. That is what
// lets render.mjs ask for frame 1,847 on its own, in any order, on any worker, and get the
// same picture the player shows.
(function () {
  const F = (window.F = {});
  // Two cuts from one film. Every scene is drawn once, in the wide film's coordinates. For a
  // phone (?tall) nothing is redrawn: the illustration and the type are each picked up whole and
  // set down again, type above and illustration below, inside Instagram's safe zones (its own
  // furniture covers about the top 250 px and the bottom 340). What cannot simply be moved (a
  // goose that comes up from the foot of the frame, a line too wide for a phone) asks F.TALL.
  F.TALL = new URLSearchParams(location.search).has("tall");
  F.W = F.TALL ? 1080 : 1920;
  F.H = F.TALL ? 1920 : 1080;
  F.FPS = 60;
  const SEAT = F.TALL ? { world: { s: 1.06, fx: 1400, fy: 624, x: 540, y: 1248 }, type: { s: 0.8, fx: 0, fy: 0, x: -33.6, y: 58 } } : null;

  // ---- numbers ---------------------------------------------------------------------------
  F.clamp = (x, a = 0, b = 1) => (x < a ? a : x > b ? b : x);
  F.lerp = (a, b, t) => a + (b - a) * t;
  // How far t has got through [a, b], clamped. The one function every beat is built from.
  F.prog = (t, a, b) => F.clamp((t - a) / (b - a));

  // A CSS cubic-bezier as a function, solved with a few Newton steps and bisection behind
  // them, which is how browsers do it.
  F.bez = (x1, y1, x2, y2) => {
    const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
    const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
    const X = (u) => ((ax * u + bx) * u + cx) * u;
    const Y = (u) => ((ay * u + by) * u + cy) * u;
    const dX = (u) => (3 * ax * u + 2 * bx) * u + cx;
    return (x) => {
      if (x <= 0) return 0;
      if (x >= 1) return 1;
      let u = x;
      for (let i = 0; i < 6; i++) {
        const e = X(u) - x;
        if (Math.abs(e) < 1e-5) return Y(u);
        const d = dX(u);
        if (Math.abs(d) < 1e-6) break;
        u -= e / d;
      }
      let lo = 0, hi = 1;
      u = x;
      for (let i = 0; i < 24; i++) {
        const e = X(u) - x;
        if (Math.abs(e) < 1e-5) break;
        if (e > 0) hi = u; else lo = u;
        u = (lo + hi) / 2;
      }
      return Y(u);
    };
  };

  // Material's emphasized set for anything that arrives or leaves, and a handful of
  // overshoots for anything that should feel like it has weight.
  F.E = {
    lin: (t) => t,
    out: F.bez(0.05, 0.7, 0.1, 1),
    in: F.bez(0.3, 0, 0.8, 0.15),
    io: F.bez(0.65, 0, 0.35, 1),
    std: F.bez(0.2, 0, 0, 1),
    soft: F.bez(0.4, 0, 0.2, 1),
    quadOut: (t) => 1 - (1 - t) * (1 - t),
    cubicOut: (t) => 1 - Math.pow(1 - t, 3),
    cubicIn: (t) => t * t * t,
    back: (s = 1.7) => (t) => 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2),
    // A damped spring that has settled by t = 1. zeta under 1 rings, omega sets how often.
    spring: (zeta = 0.45, omega = 14) => (t) => {
      if (t <= 0) return 0;
      if (t >= 1) return 1;
      const wd = omega * Math.sqrt(1 - zeta * zeta);
      const env = Math.exp(-zeta * omega * t);
      const v = 1 - env * (Math.cos(wd * t) + ((zeta * omega) / wd) * Math.sin(wd * t));
      // Fade the last of the ringing out so the value lands on 1 exactly.
      const k = F.clamp((t - 0.8) / 0.2);
      return v + (1 - v) * k;
    },
  };

  // Eased progress through a window: tw(t, start, duration, ease).
  F.tw = (t, t0, dur, ease = F.E.out) => ease(F.prog(t, t0, t0 + dur));

  // Piecewise keyframes: [[time, value], [time, value, ease], ...]. The ease on a key is
  // the one used to arrive at it.
  F.keys = (t, ks) => {
    if (t <= ks[0][0]) return ks[0][1];
    for (let i = 1; i < ks.length; i++) {
      if (t <= ks[i][0]) {
        const [t0, v0] = ks[i - 1];
        const [t1, v1, e] = ks[i];
        return F.lerp(v0, v1, (e || F.E.io)(F.prog(t, t0, t1)));
      }
    }
    return ks[ks.length - 1][1];
  };

  // Seeded, so a render is repeatable.
  F.rng = (seed) => {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) >>> 0;
      let x = Math.imul(a ^ (a >>> 15), 1 | a);
      x = (x + Math.imul(x ^ (x >>> 7), 61 | x)) ^ x;
      return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
    };
  };

  // ---- colour ----------------------------------------------------------------------------
  const hex2 = (h) => {
    const s = h.replace("#", "");
    return [0, 2, 4].map((i) => parseInt(s.slice(i, i + 2), 16));
  };
  F.mixHex = (a, b, t) => {
    const A = hex2(a), B = hex2(b);
    const c = A.map((v, i) => Math.round(F.lerp(v, B[i], F.clamp(t))));
    return "#" + c.map((v) => v.toString(16).padStart(2, "0")).join("");
  };

  // ---- svg -------------------------------------------------------------------------------
  const NS = "http://www.w3.org/2000/svg";
  F.el = (tag, attrs, parent) => {
    const n = document.createElementNS(NS, tag);
    if (attrs) for (const k in attrs) if (attrs[k] != null) n.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(n);
    return n;
  };
  F.g = (parent, attrs) => F.el("g", attrs, parent);
  F.set = (n, attrs) => {
    for (const k in attrs) n.setAttribute(k, attrs[k]);
    return n;
  };
  const r3 = (v) => Math.round(v * 1000) / 1000;
  // Place a node: translate, then rotate, then scale, about its own origin.
  F.T = (n, x = 0, y = 0, rot = 0, sx = 1, sy = sx) => {
    n.setAttribute(
      "transform",
      `translate(${r3(x)} ${r3(y)}) rotate(${r3(rot)}) scale(${r3(sx)} ${r3(sy)})`
    );
    return n;
  };
  F.show = (n, on) => {
    const want = on ? "" : "none";
    if (n.style.display !== want) n.style.display = want;
  };
  F.op = (n, o) => n.setAttribute("opacity", r3(F.clamp(o)));

  // Seat a scene's illustration ("world") or its type in this cut. In the wide cut, where they are.
  // `over` is for a scene with more room than most: no goose above its picture, so bigger type.
  F.seat = (node, which, over) => {
    const k = SEAT && Object.assign({}, SEAT[which], over);
    if (k) node.setAttribute("transform", `translate(${r3(k.x - k.fx * k.s)} ${r3(k.y - k.fy * k.s)}) scale(${k.s})`);
    return node;
  };
  // A point of the illustration as a point on this cut's stage, and back again.
  F.toStage = (x, y) => (SEAT ? [SEAT.world.x + (x - SEAT.world.fx) * SEAT.world.s, SEAT.world.y + (y - SEAT.world.fy) * SEAT.world.s] : [x, y]);
  F.toWorld = (x, y) => (SEAT ? [SEAT.world.fx + (x - SEAT.world.x) / SEAT.world.s, SEAT.world.fy + (y - SEAT.world.y) / SEAT.world.s] : [x, y]);

  let uid = 0;
  F.id = (p = "id") => `${p}${++uid}`;

  // ---- cues ------------------------------------------------------------------------------
  // The film's own account of when things happen, for the soundtrack to be cut to. A scene
  // calls F.cue with the same constant that drives the picture, never a copy of it, so a
  // sound cannot drift from the thing it belongs to. `node render.mjs --cues` writes them out.
  F.cues = [];
  F.cue = (t, name, data) => F.cues.push(Object.assign({ t: Math.round(t * 1000) / 1000, name }, data));

  // A rounded rectangle as a path, centred on the origin unless told otherwise.
  F.rr = (w, h, r, x = -w / 2, y = -h / 2) => {
    r = Math.min(r, w / 2, h / 2);
    return `M${x + r},${y} h${w - 2 * r} a${r},${r} 0 0 1 ${r},${r} v${h - 2 * r} a${r},${r} 0 0 1 ${-r},${r} h${-(w - 2 * r)} a${r},${r} 0 0 1 ${-r},${-r} v${-(h - 2 * r)} a${r},${r} 0 0 1 ${r},${-r} Z`;
  };

  // ---- blobs -----------------------------------------------------------------------------
  // A closed smooth curve through points on a wobbly circle. Radii are returned separately
  // from the path so two blobs with the same point count can be morphed by mixing radii.
  F.blobRadii = (seed, n = 9, jitter = 0.2) => {
    const r = F.rng(seed);
    return Array.from({ length: n }, () => 1 + (r() * 2 - 1) * jitter);
  };
  F.blobPath = (radii, R, breathe = 0, time = 0, squashX = 1, squashY = 1) => {
    const n = radii.length;
    const pts = radii.map((k, i) => {
      const a = (i / n) * Math.PI * 2;
      const rr = R * (k + breathe * Math.sin(time * 1.3 + i * 2.1));
      return [Math.cos(a) * rr * squashX, Math.sin(a) * rr * squashY];
    });
    // Catmull-Rom through the points, written out as cubic Beziers.
    let d = "";
    for (let i = 0; i < n; i++) {
      const p0 = pts[(i - 1 + n) % n], p1 = pts[i], p2 = pts[(i + 1) % n], p3 = pts[(i + 2) % n];
      const c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
      const c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
      if (i === 0) d += `M${r3(p1[0])},${r3(p1[1])}`;
      d += ` C${r3(c1[0])},${r3(c1[1])} ${r3(c2[0])},${r3(c2[1])} ${r3(p2[0])},${r3(p2[1])}`;
    }
    return d + " Z";
  };
  F.mixRadii = (a, b, t) => a.map((v, i) => F.lerp(v, b[i], t));

  // ---- type ------------------------------------------------------------------------------
  F.text = (parent, str, o = {}) => {
    const n = F.el(
      "text",
      {
        x: o.x || 0,
        y: o.y || 0,
        "font-family": o.family || "Jost",
        "font-size": o.size || 40,
        "font-weight": o.weight || 500,
        "font-style": o.italic ? "italic" : null,
        fill: o.fill || "#000",
        "text-anchor": o.anchor || "start",
        "letter-spacing": o.ls != null ? o.ls : null,
        "dominant-baseline": o.baseline || null,
      },
      parent
    );
    n.textContent = str;
    return n;
  };

  // A headline of one or more lines, each rising out of its own mask. pIn and pOut are
  // 0..1 for the whole block; lines are staggered inside them.
  F.headline = (parent, o) => {
    const size = o.size || 150, lh = o.lh || size * 1.02, stagger = o.stagger ?? 0.18;
    const root = F.g(parent);
    F.T(root, o.x, o.y);
    const lines = o.lines.map((ln, i) => {
      const clipId = F.id("hl");
      const cp = F.el("clipPath", { id: clipId }, root);
      F.el("rect", { x: -1000, y: i * lh - size * 0.98, width: 3000, height: size * 1.42 }, cp);
      const wrap = F.g(root, { "clip-path": `url(#${clipId})` });
      const tx = F.text(wrap, ln.text, {
        x: 0, y: i * lh, size, weight: o.weight || 600, fill: ln.fill, ls: o.ls ?? "-0.025em",
        anchor: o.anchor,
      });
      return { tx, base: i * lh };
    });
    const n = lines.length;
    return {
      root,
      update(pIn, pOut = 0) {
        lines.forEach((l, i) => {
          const a = F.E.out(F.clamp((pIn - (i * stagger)) / (1 - (n - 1) * stagger)));
          const b = F.E.in(F.clamp((pOut - (i * stagger)) / (1 - (n - 1) * stagger)));
          l.tx.setAttribute("y", r3(l.base + (1 - a) * size * 1.35 - b * size * 1.5));
        });
      },
    };
  };
})();
