#!/usr/bin/env python3
"""Record each beat of the promo from the emulator, one clip per beat, into raw/.

Release package, light theme, demo-mode status bar at 09:30 (02:00 for the dark beats).
Every coordinate is located with uiautomator *before* the recorder starts, because a dump
costs 2-5 s and inside a clip that is dead air. Two recorder backends:

  shell  adb shell screenrecord   VFR up to ~120 fps, but on this emulator it stops receiving
                                  frames once ViewerActivity's window is created
  emu    adb emu screenrecord     the emulator's own framebuffer capture, VP9 webm, constant fps

    python3 shoot.py test                      # tries both on the viewer, writes raw/.backend
    python3 shoot.py open-pdf open-docx ...    # beats; see BEATS at the bottom
"""
import os, sys, time, subprocess
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..", "scripts", "screenshots"))
import ui
ui.PKG = "com.arjun.gander"
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
ADB = ui.ADB
FILES = {"pdf": "Tenancy Agreement", "docx": "Field Survey", "xlsx": "Q3 Operating",
         "pptx": "Willowmere Kickoff", "jpg": "IMG_20260214", "md": "Willowmere site notes"}


def backend():
    p = os.path.join(RAW, ".backend")
    return open(p).read().strip() if os.path.exists(p) else os.environ.get("GANDER_REC", "emu")


def adb(*a):
    return subprocess.run([ADB] + list(a), capture_output=True, text=True)


def tap(x, y, wait=0.0):
    adb("shell", f"input tap {x} {y}")
    if wait: time.sleep(wait)


def swipe(x1, y1, x2, y2, ms, wait=0.0):
    adb("shell", f"input swipe {x1} {y1} {x2} {y2} {ms}")
    if wait: time.sleep(wait)


def locate(text, exact=False, min_y=None, cls=None, tries=3):
    for _ in range(tries):
        n = ui.by_text(text, exact, min_y=min_y, cls=cls)
        if n is not None:
            return ui._center(n.get("bounds"))
        time.sleep(1.0)
    raise RuntimeError(f"cannot locate {text!r}; visible: " + " / ".join(ui.texts()[:12]))


def home():
    for _ in range(6):
        if ui.by_text("Recent files") is not None and ui.by_text("Add a folder") is not None:
            return True
        ui.back(0.9)
    ui.launch()
    return ui.by_text("Recent files") is not None


def row(key):
    """Centre of the file's row on the home screen."""
    return locate(FILES[key], min_y=400, cls="TextView")


def open_recent(key, wait=4.0):
    tap(*row(key), wait=wait)


# Beats in which a ViewerActivity window is created while recording. adb shell screenrecord
# stops receiving frames at that moment on this emulator (an already-open viewer records fine),
# so these always use the emulator's own capture.
TRANSITION = ("open-", "folder", "md-light", "dark")


class Rec:
    def __init__(self, name):
        self.name = name
        self.kind = "emu" if name.startswith(TRANSITION) else backend()
        self.local = os.path.join(RAW, f"{name}.{'webm' if self.kind == 'emu' else 'mp4'}")
        self.remote = f"/sdcard/promo-{name}.mp4"

    def __enter__(self):
        if self.kind == "emu":
            if os.path.exists(self.local): os.remove(self.local)
            r = adb("emu", "screenrecord", "start", self.local)
            if "OK" not in r.stdout: raise RuntimeError("emu screenrecord: " + r.stdout + r.stderr)
            time.sleep(0.5)
        else:
            adb("shell", "rm", "-f", self.remote)
            self.p = subprocess.Popen([ADB, "shell", "screenrecord", "--bit-rate", "24000000",
                                       "--time-limit", "120", self.remote],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(0.9)
        self.t0 = time.time()
        return self

    def mark(self, label):
        print(f"  {label} @ {time.time() - self.t0:5.2f}s", flush=True)

    def __exit__(self, *a):
        time.sleep(0.3)
        if self.kind == "emu":
            adb("emu", "screenrecord", "stop")
            for _ in range(40):
                time.sleep(0.5)
                if os.path.exists(self.local) and os.path.getsize(self.local) > 0:
                    s = os.path.getsize(self.local); time.sleep(0.8)
                    if os.path.getsize(self.local) == s: break
        else:
            adb("shell", "pkill", "-INT", "screenrecord"); self.p.wait(timeout=15); time.sleep(0.6)
            adb("pull", self.remote, self.local)
        print("wrote", self.local, flush=True)


def frames(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                        "-show_entries", "stream=nb_read_frames,avg_frame_rate", "-of", "csv=p=0", path],
                       capture_output=True, text=True)
    return r.stdout.strip()


def beat_test():
    """Open the PDF, then scroll it under each recorder; the one that sees motion wins."""
    home(); open_recent("pdf", wait=4.5)
    results = {}
    for kind in ("shell", "emu"):
        os.environ["GANDER_REC"] = kind
        p = os.path.join(RAW, ".backend")
        if os.path.exists(p): os.remove(p)
        with Rec(f"test-{kind}") as r:
            time.sleep(0.4); r.mark("scroll")
            swipe(540, 1400, 540, 700, 500, wait=1.0)
            swipe(540, 700, 540, 1400, 500, wait=0.8)
        results[kind] = frames(r.local); print(kind, results[kind], flush=True)
    n = lambda k: int(results[k].split(",")[0] or 0)
    chosen = "shell" if n("shell") >= 20 else "emu"
    open(os.path.join(RAW, ".backend"), "w").write(chosen)
    print("BACKEND", chosen, flush=True)
    home()


def beat_open(key):
    home(); time.sleep(0.6); x, y = row(key)
    with Rec(f"open-{key}") as r:
        time.sleep(2.2 if key == "pdf" else 0.9)     # the pdf clip doubles as the opening hold
        r.mark("tap"); tap(x, y)
        time.sleep(3.6)


def beat_about():
    home(); mx, my = locate("More options")
    tap(mx, my, wait=1.2); ax, ay = locate("About Gander"); ui.back(0.8)
    with Rec("about") as r:
        time.sleep(0.5); r.mark("menu"); tap(mx, my, wait=0.9)
        r.mark("about"); tap(ax, ay)
        time.sleep(3.6)
    ui.back(0.8)


def beat_appinfo():
    home()
    adb("shell", "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.arjun.gander")
    time.sleep(3.5); px, py = locate("Permissions", exact=True, min_y=300)
    ui.back(0.6); home(); time.sleep(0.5)               # Settings stays warm behind Gander
    with Rec("appinfo") as r:
        time.sleep(0.5); r.mark("launch")
        adb("shell", "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.arjun.gander")
        time.sleep(2.4); r.mark("tap permissions"); tap(px, py)
        time.sleep(3.0)
    ui.back(0.5); ui.back(0.5); home()


def beat_find():
    home(); open_recent("pdf", wait=4.5)
    sx, sy = locate("Search in document")
    tap(sx, sy, wait=1.2); nx, ny = locate("Next match")           # dry pass for the chevron
    for _ in range(3):                                              # keyboard, then the search bar
        if ui.by_text("Close search") is None: break
        ui.back(0.9)
    time.sleep(0.6)
    with Rec("find") as r:
        time.sleep(0.6); r.mark("search"); tap(sx, sy, wait=1.0)
        r.mark("type")
        for ch in "deposit":
            adb("shell", f"input text {ch}"); time.sleep(0.12)
        time.sleep(1.3); r.mark("keyboard down"); ui.back(1.3)      # Back drops the keyboard only
        r.mark("next")
        for _ in range(3): tap(nx, ny, wait=1.1)
        time.sleep(0.8)
    ui.back(0.6); ui.back(0.6); home()


def beat_sheet():
    home(); open_recent("xlsx", wait=4.5)
    dx, dy = locate("Depots", exact=True); hx, hy = locate("Headcount", exact=True)
    with Rec("sheet") as r:
        time.sleep(0.6); r.mark("scroll"); swipe(540, 1400, 540, 1050, 420, wait=1.1)
        r.mark("depots"); tap(dx, dy, wait=1.7)
        r.mark("headcount"); tap(hx, hy, wait=1.9)
    home()


def beat_deck():
    home(); open_recent("pptx", wait=5.0)
    with Rec("deck") as r:
        time.sleep(0.6); r.mark("scroll")
        swipe(540, 1500, 540, 700, 700, wait=1.3)
        swipe(540, 1500, 540, 700, 700, wait=1.8)
    home()


def beat_folder():
    home(); fx, fy = locate("Documents", exact=True, min_y=600)
    tap(fx, fy, wait=2.0); ix, iy = locate("IMG_20260214", min_y=280, cls="TextView"); ui.back(1.0)
    with Rec("folder") as r:
        time.sleep(0.5); r.mark("documents"); tap(fx, fy, wait=2.3)
        r.mark("photo"); tap(ix, iy)
        time.sleep(3.0)
    home()


def clock(hhmm):
    adb("shell", f"am broadcast -a com.android.systemui.demo -e command clock -e hhmm {hhmm}")


def beat_md_light():
    home(); x, y = row("md")
    with Rec("md-light") as r:
        time.sleep(0.4); r.mark("tap"); tap(x, y)
        time.sleep(3.2)
    home()


def beat_dark():
    """Dark mode: the notes file opens from a dark home, clock reading 02:00."""
    adb("shell", "cmd uimode night yes"); clock("0200"); time.sleep(1.5); home(); time.sleep(0.8)
    x, y = row("md")
    with Rec("dark") as r:
        time.sleep(1.0); r.mark("tap"); tap(x, y)
        time.sleep(3.4)
    home(); adb("shell", "cmd uimode night no"); clock("0930"); time.sleep(1.0); home()


BEATS = ["open-pdf", "open-docx", "open-xlsx", "open-pptx", "open-jpg", "about", "appinfo",
         "find", "sheet", "deck", "folder", "md-light", "dark"]

if __name__ == "__main__":
    os.makedirs(RAW, exist_ok=True)
    ui.demo_mode()
    names = BEATS if sys.argv[1:] == ["all"] else sys.argv[1:]
    for b in names:
        print("==", b, flush=True)
        try:
            if b.startswith("open-"): beat_open(b[5:])
            else: globals()["beat_" + b.replace("-", "_")]()
        except Exception as e:
            print("FAILED", b, repr(e), flush=True)
            home()
