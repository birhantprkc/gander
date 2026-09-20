// Mounts the scenes and exposes F.renderAt(seconds). Opened plainly, the page is a player
// with a scrub bar; opened with ?render it shows nothing but the stage and waits to be
// stepped by render.mjs.
(function () {
  const F = window.F;
  const q = new URLSearchParams(location.search);
  const RENDER = q.has("render");

  F.start = async () => {
    await Promise.all(
      ["400", "500", "600", "700", "800", "italic 500", "italic 600"].map((w) => document.fonts.load(`${w} 40px Jost`))
    );
    await document.fonts.ready;
    const svg = document.getElementById("stage");
    F.buildDefs(svg);
    const layer = F.g(svg);
    const scenes = F.scenes.slice().sort((a, b) => (a.z || 0) - (b.z || 0) || a.t0 - b.t0);
    scenes.forEach((s) => {
      s.root = F.g(layer, { id: "scene-" + s.id });
      s.update = s.build(s.root);
      F.show(s.root, false);
    });
    if (!q.has("nograin"))
      F.el("rect", { x: 0, y: 0, width: F.W, height: F.H, fill: "url(#grain)", "pointer-events": "none" }, svg);
    F.prepWobs(svg);
    F.DUR = Math.max(...scenes.map((s) => s.t1));

    F.renderAt = (t) => {
      F.boil(t);
      for (const s of scenes) {
        const on = t >= s.t0 && t < s.t1;
        F.show(s.root, on);
        if (on) s.update(t - s.t0, t);
      }
      return t;
    };

    if (RENDER) {
      document.body.classList.add("render");
      F.renderAt(0);
      window.__ready = true;
      return;
    }

    // ---- player -------------------------------------------------------------------------
    const bar = document.getElementById("scrub"), lab = document.getElementById("time");
    bar.max = F.DUR;
    let t = parseFloat(q.get("t") || "0"), playing = !q.has("t"), last = performance.now();
    const draw = () => {
      F.renderAt(t);
      bar.value = t;
      const s = scenes.filter((x) => t >= x.t0 && t < x.t1).map((x) => x.id).join(" + ");
      lab.textContent = `${t.toFixed(2)}s / ${F.DUR.toFixed(0)}s   frame ${Math.round(t * F.FPS)}   ${s}`;
    };
    const tick = (now) => {
      if (playing) {
        t += (now - last) / 1000;
        if (t >= F.DUR) t = 0;
        draw();
      }
      last = now;
      requestAnimationFrame(tick);
    };
    bar.addEventListener("input", () => { t = parseFloat(bar.value); playing = false; draw(); });
    window.addEventListener("keydown", (e) => {
      if (e.code === "Space") { playing = !playing; e.preventDefault(); }
      if (e.code === "ArrowRight") { playing = false; t = Math.min(F.DUR - 1 / F.FPS, t + (e.shiftKey ? 1 : 1 / F.FPS)); draw(); }
      if (e.code === "ArrowLeft") { playing = false; t = Math.max(0, t - (e.shiftKey ? 1 : 1 / F.FPS)); draw(); }
    });
    draw();
    requestAnimationFrame(tick);
  };

  window.addEventListener("load", () => F.start());
})();
