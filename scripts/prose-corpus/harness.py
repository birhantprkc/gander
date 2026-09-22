"""
Runs a reader over every corpus file of its format and scores what came out.

    python3 harness.py <work> <ext> <label> [--adapter page.html] [--shots] [--only substring]

<work> is the directory fetch_wild.py, make_gen.sh and make_refs.sh filled. The
adapter is a page that takes ?file=<url>, draws the file into #out and then sets
window.__done = true, or window.__error to a string; the default, ours.html beside
this script, loads the readers straight out of app/src/main/assets/viewer, so a
change there is measured as it is. Results go to <work>/out/<label>/: a text dump
and, with --shots, a screenshot per file, and results.json.

Scored against LibreOffice's own reading of the same file (reftxt/), as word
multisets: recall is how much of the document's text reached the page, precision
is how much of what reached the page belongs to the document. A renderer that
leaks field codes or binary junk keeps its recall and loses precision.
"""

import argparse
import collections
import functools
import http.server
import json
import re
import threading
import time
import unicodedata
from pathlib import Path

from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SETS = ["gen", "wild/doc", "wild/rtf", "wild/odt", "extra"]
ROOT = None


class Quiet(http.server.SimpleHTTPRequestHandler):
    """Serves the work directory at /, the viewer at /viewer/ and this directory at /harness/."""

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


def serve():
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def words(text):
    text = unicodedata.normalize("NFKC", text).lower()
    # CJK has no spaces, so each ideograph counts as a word of its own
    text = re.sub(r"([぀-ヿ㐀-鿿가-힯])", r" \1 ", text)
    return collections.Counter(re.findall(r"[^\W_]+", text, re.UNICODE))


def score(ref, got):
    r, g = words(ref), words(got)
    both = sum((r & g).values())
    recall = both / max(1, sum(r.values()))
    precision = both / max(1, sum(g.values()))
    return round(recall, 3), round(precision, 3), sum(r.values()), sum(g.values())


def main():
    global ROOT
    ap = argparse.ArgumentParser()
    ap.add_argument("work")
    ap.add_argument("ext")
    ap.add_argument("label")
    ap.add_argument("--adapter", default="/harness/ours.html")
    ap.add_argument("--shots", action="store_true")
    ap.add_argument("--only", default="")
    ap.add_argument("--timeout", type=int, default=20)
    args = ap.parse_args()
    ROOT = Path(args.work).resolve()

    files = []
    for s in SETS:
        d = ROOT / "corpus" / s
        if d.is_dir():
            files += sorted(p for p in d.iterdir()
                            if p.suffix.lower() == "." + args.ext and args.only in p.name)

    httpd = serve()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    out_dir = ROOT / "out" / args.label
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        for f in files:
            rel = f.relative_to(ROOT).as_posix()
            ctx = browser.new_context(viewport={"width": 980, "height": 1600})
            page = ctx.new_page()
            errors = []
            page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
            page.on("pageerror", lambda e: errors.append("pageerror: " + str(e)))
            t0 = time.time()
            status = "ok"
            try:
                page.goto(f"{base}{args.adapter}?file=/{rel}")
                page.wait_for_function("() => window.__done || window.__error",
                                       timeout=args.timeout * 1000)
                err = page.evaluate("() => window.__error || null")
                if err:
                    status = "error: " + str(err)[:200]
            except Exception as e:   # noqa: BLE001
                status = "timeout" if "Timeout" in str(e) else "crash: " + str(e)[:200]
            ms = int((time.time() - t0) * 1000)
            try:
                got = page.evaluate("() => (document.querySelector('#out') || document.body).innerText")
            except Exception:   # noqa: BLE001
                got = ""
            ref_path = ROOT / "reftxt" / f.parent.relative_to(ROOT / "corpus") / (f.stem + ".txt")
            ref = ref_path.read_text("utf8", "replace") if ref_path.exists() else None
            rec = {"file": rel, "status": status, "ms": ms, "chars": len(got),
                   "errors": errors[:5]}
            if ref is not None:
                rec["recall"], rec["precision"], rec["ref_words"], rec["got_words"] = score(ref, got)
            if args.shots:
                shot = out_dir / (f.parent.name + "-" + f.stem + ".png")
                try:
                    page.screenshot(path=str(shot), full_page=True,
                                    clip={"x": 0, "y": 0, "width": 980, "height": 2400})
                except Exception:   # noqa: BLE001
                    try:
                        page.screenshot(path=str(shot))
                    except Exception:   # noqa: BLE001
                        pass
            (out_dir / (f.parent.name + "-" + f.stem + ".txt")).write_text(got, "utf8")
            results.append(rec)
            ctx.close()
        browser.close()
    httpd.shutdown()

    (out_dir / "results.json").write_text(json.dumps(results, indent=1), "utf8")
    scored = [r for r in results if "recall" in r and r["ref_words"] >= 5]
    ok = [r for r in results if r["status"] == "ok"]
    print(f"{args.label}: {len(files)} files, {len(ok)} rendered without error")
    if scored:
        print(f"  mean recall {sum(r['recall'] for r in scored)/len(scored):.3f}"
              f"  mean precision {sum(r['precision'] for r in scored)/len(scored):.3f}"
              f"  over {len(scored)} files with a reference")
        print(f"  median ms {sorted(r['ms'] for r in results)[len(results)//2]}")
    worst = sorted(scored, key=lambda r: r["recall"])[:12]
    for r in worst:
        print(f"    {r['recall']:.2f}/{r['precision']:.2f}  {r['status'][:40]:40}  {r['file']}")


if __name__ == "__main__":
    main()
