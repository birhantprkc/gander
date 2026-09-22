"""
Throws broken files at the prose readers and sorts what happens.

    python3 fuzz.py <work> [--count 2000] [--seed 1] [--timeout 10] [--only substring]

Takes every .doc, .rtf and .odt under <work>/corpus and the three fixtures, makes
--count variants of them with bytes flipped, blocks zeroed, the file cut short, or a
length or offset field inflated, and opens each in the page with a timeout. A reader
meeting a file it cannot read has two acceptable answers, the document or the card,
and a fixed budget of time and memory to give either. Anything else is a bug, and the
variant that found it is kept under <work>/fuzz/found/ to reproduce it with.

The inflated-field mutation is the one that matters. The other three make noise a
parser must survive; this one makes the numbers a parser trusts lie to it, which is
how a crafted file would.
"""

import argparse
import http.server
import json
import random
import struct
import threading
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FIXTURES = REPO / "tests" / "fixtures" / "files"
ROOT = None

BIG = [0xFFFF, 0x7FFF, 0x8000, 0xFFFFFFFF, 0x7FFFFFFF, 0x80000000, 0x10000000, 0x0FFFFFFF]


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):   # noqa: A002
        pass

    def translate_path(self, path):
        path = path.split("?", 1)[0]
        if path.startswith("/viewer/"):
            return str(REPO / "app/src/main/assets/viewer" / path[len("/viewer/"):])
        if path.startswith("/harness/"):
            return str(HERE / path[len("/harness/"):])
        return str(ROOT / path.lstrip("/"))

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def mutate(data: bytes, rng: random.Random) -> tuple[bytes, str]:
    out = bytearray(data)
    n = len(out)
    kind = rng.choice(["flip", "flip", "zero", "cut", "inflate", "inflate", "inflate", "shuffle"])
    if n < 16:
        return bytes(out), "tiny"
    if kind == "flip":
        for _ in range(rng.randint(1, max(1, n // 200))):
            out[rng.randrange(n)] ^= 1 << rng.randrange(8)
    elif kind == "zero":
        at = rng.randrange(n)
        length = rng.randint(1, min(512, n - at))
        out[at:at + length] = bytes(length)
    elif kind == "cut":
        out = out[:rng.randrange(1, n)]
    elif kind == "inflate":
        for _ in range(rng.randint(1, 4)):
            at = rng.randrange(n - 4)
            width = rng.choice([1, 2, 4])
            value = rng.choice(BIG) & ((1 << (8 * width)) - 1)
            out[at:at + width] = struct.pack("<I", value)[:width]
    elif kind == "shuffle":
        a, b = sorted(rng.sample(range(n), 2))
        length = rng.randint(1, min(256, n - b))
        out[a:a + length], out[b:b + length] = out[b:b + length], out[a:a + length]
    return bytes(out), kind


def main():
    global ROOT
    ap = argparse.ArgumentParser()
    ap.add_argument("work")
    ap.add_argument("--count", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--only", default="")
    ap.add_argument("--adapter", default="/harness/ours.html")
    args = ap.parse_args()
    ROOT = Path(args.work).resolve()
    rng = random.Random(args.seed)

    sources = []
    for d in [ROOT / "corpus" / "gen", ROOT / "corpus" / "wild" / "doc", ROOT / "corpus" / "wild" / "rtf",
              ROOT / "corpus" / "wild" / "odt", FIXTURES]:
        if d.is_dir():
            sources += [p for p in sorted(d.iterdir())
                        if p.suffix.lower() in (".doc", ".rtf", ".odt") and p.stat().st_size < 400_000
                        and args.only in p.name]
    if not sources:
        raise SystemExit("no source files")

    fuzz_dir = ROOT / "fuzz"
    found_dir = fuzz_dir / "found"
    fuzz_dir.mkdir(parents=True, exist_ok=True)
    found_dir.mkdir(exist_ok=True)
    (fuzz_dir / "current").mkdir(exist_ok=True)

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"

    tally = {"drew": 0, "card": 0, "hang": 0, "escaped": 0, "memory": 0, "crash": 0}
    findings = []
    t_start = time.time()

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={"width": 980, "height": 1600})
        errors = []
        page = context.new_page()
        page.on("pageerror", lambda e: errors.append(str(e)))
        for i in range(args.count):
            src = rng.choice(sources)
            data, kind = mutate(src.read_bytes(), rng)
            name = f"{i:05d}-{kind}-{src.name}"
            current = fuzz_dir / "current" / ("case" + src.suffix.lower())
            current.write_bytes(data)
            rel = current.relative_to(ROOT).as_posix()

            del errors[:]
            outcome = "crash"
            detail = ""
            t0 = time.time()
            try:
                page.goto(f"{base}{args.adapter}?file=/{rel}&v={i}", timeout=args.timeout * 1000)
                page.wait_for_function("() => window.__done || window.__error", timeout=args.timeout * 1000)
                err = page.evaluate("() => window.__error || null")
                err_name = page.evaluate("() => window.__errorName || null")
                # A reader's own refusal is thrown as a plain Error with a sentence in it, and
                # is a card. A TypeError or RangeError is a bug, wherever it was caught
                if err and (str(err).startswith("onerror:") or err_name != "Error"):
                    outcome, detail = "escaped", str(err)[:200]
                elif err:
                    outcome, detail = "card", str(err)[:120]
                else:
                    outcome = "drew"
                heap = page.evaluate("() => performance.memory ? performance.memory.usedJSHeapSize : 0")
                if heap > 512 * 1024 * 1024:
                    outcome, detail = "memory", f"{heap // (1024 * 1024)} MB heap"
            except Exception as e:   # noqa: BLE001
                text = str(e)
                if "Timeout" in text:
                    outcome, detail = "hang", f"{time.time() - t0:.1f}s"
                else:
                    outcome, detail = "crash", text[:200]
                # The page may be wedged; start again on a fresh one
                try:
                    page.close()
                except Exception:   # noqa: BLE001
                    pass
                page = context.new_page()
                page.on("pageerror", lambda e: errors.append(str(e)))
            if errors and outcome in ("drew", "card"):
                outcome, detail = "escaped", errors[0][:200]

            tally[outcome] += 1
            if outcome not in ("drew", "card"):
                kept = found_dir / name
                kept.write_bytes(data)
                findings.append({"file": kept.name, "outcome": outcome, "detail": detail, "source": src.name})
                print(f"  {outcome:8} {detail[:90]:90} {name}", flush=True)
            if (i + 1) % 100 == 0:
                print(f"{i + 1}/{args.count}  {tally}  {time.time() - t_start:.0f}s", flush=True)
        browser.close()
    httpd.shutdown()

    (fuzz_dir / "results.json").write_text(json.dumps({"tally": tally, "findings": findings}, indent=1))
    print("done:", tally)
    print(f"{len(findings)} kept under {found_dir}")


if __name__ == "__main__":
    main()
