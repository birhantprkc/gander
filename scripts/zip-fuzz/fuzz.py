"""
Throws broken zips at the zip reader and sorts what happens.

    python3 fuzz.py <work> [--count 6000] [--seed 1] [--timeout 10] [--only substring]
    python3 fuzz.py <work> --replay <work>/fuzz/found/<file>

What scripts/prose-corpus/fuzz.py does for the prose readers, for ZipReader, Deflate64,
ZipEncryption and ZipNames. The seeds are every zip among the fixtures (a .docx, .xlsx, .pptx
and .odt is one too), archives from make_fixtures.py's own writer in every mix of method,
lock, trailing sizes and ZIP64, and whatever 7-Zip and Info-ZIP write, if installed. A case is:

  bytes      the prose fuzzer's mutations of a seed: bytes flipped, a block zeroed, the file
             cut short, a number inflated, two blocks swapped
  field      one number in one of a seed's records (a count, size, offset, length, method or
             flag) set to one it should not hold. The records are a few hundred bytes at the end
             of a file that random bytes rarely reach, and they are the numbers the reader trusts
  made       an archive made up there and then: names of random bytes, names thousands of
             folders deep, names that clash, every method and lock at random
  deflate64  a seed's raw Deflate64 stream, mutated and read with no zip around it, whose own
             checks would stop a damaged stream early and hide the decoder behind them
  names      names and passwords made up as bytes and read under every code page

ZipFuzz.kt, compiled with the unit tests, reads each case as Gander would. Read and refused are
the two acceptable answers. Escaped (anything thrown but an IOException), invariant (a path,
size or read the app is promised never to see), memory, a hang past --timeout and a crash are
bugs, and the case that found one is kept under <work>/fuzz/found/ to replay.
"""

import argparse
import json
import queue
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FIXTURES = REPO / "tests" / "fixtures" / "files"
sys.path.insert(0, str(REPO / "tests" / "fixtures"))
import make_fixtures as mf  # noqa: E402  the fixtures' own zip writer

BIG = [0xFFFF, 0x7FFF, 0x8000, 0xFFFFFFFF, 0x7FFFFFFF, 0x80000000, 0x10000000, 0x0FFFFFFF]
PASSWORD = "gander"
ARCHIVES = (".zip", ".docx", ".xlsx", ".pptx", ".odt")
MODES = {"bytes": 30, "field": 30, "made": 15, "deflate64": 15, "names": 10}


def mutate(data: bytes, rng: random.Random) -> tuple[bytes, str]:
    """scripts/prose-corpus/fuzz.py's mutations, unchanged."""
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


# ---------------------------------------------------------------------------
# The numbers a zip reader trusts, found by walking a zip's records
# ---------------------------------------------------------------------------

END = [(4, 2, "end.disk"), (6, 2, "end.index-disk"), (8, 2, "end.entries-here"), (10, 2, "end.entries"),
       (12, 4, "end.index-size"), (16, 4, "end.index-offset"), (20, 2, "end.comment-length")]
ZIP64_END = [(4, 8, "zip64.record-size"), (16, 4, "zip64.disk"), (20, 4, "zip64.index-disk"),
             (24, 8, "zip64.entries-here"), (32, 8, "zip64.entries"), (40, 8, "zip64.index-size"),
             (48, 8, "zip64.index-offset")]
CENTRAL = [(4, 2, "central.made-by"), (8, 2, "central.flags"), (10, 2, "central.method"),
           (12, 2, "central.time"), (14, 2, "central.date"), (16, 4, "central.crc"),
           (20, 4, "central.compressed"), (24, 4, "central.size"), (28, 2, "central.name-length"),
           (30, 2, "central.extra-length"), (32, 2, "central.comment-length"),
           (38, 4, "central.external"), (42, 4, "central.offset")]
LOCAL = [(6, 2, "local.flags"), (8, 2, "local.method"), (10, 2, "local.time"), (14, 4, "local.crc"),
         (18, 4, "local.compressed"), (22, 4, "local.size"), (26, 2, "local.name-length"),
         (28, 2, "local.extra-length")]


def fields(data: bytes) -> list[tuple[int, int, str]]:
    out = []
    end = data.rfind(b"PK\x05\x06")
    if end < 0 or end + 22 > len(data):
        return out
    out += [(end + o, w, what) for o, w, what in END]
    shift = 0
    locator = end - 20
    if locator >= 0 and data[locator:locator + 4] == b"PK\x06\x07":
        out += [(locator + 4, 4, "locator.disk"), (locator + 8, 8, "locator.record-offset"),
                (locator + 16, 4, "locator.disks")]
        for r in {struct.unpack_from("<Q", data, locator + 8)[0], locator - 56}:
            if 0 <= r <= len(data) - 56 and data[r:r + 4] == b"PK\x06\x06":
                out += [(r + o, w, what) for o, w, what in ZIP64_END]
    offset = struct.unpack_from("<I", data, end + 16)[0]
    at = offset
    if data[at:at + 4] != b"PK\x01\x02":
        # Something in front of the zip, or ZIP64's all-ones: find the index the slow way
        at = data.find(b"PK\x01\x02")
        shift = at - offset if at >= 0 and offset != 0xFFFFFFFF else 0
    walked = 0
    while 0 <= at <= len(data) - 46 and data[at:at + 4] == b"PK\x01\x02" and walked < 400:
        name, extra, comment = struct.unpack_from("<HHH", data, at + 28)
        out += [(at + o, w, what) for o, w, what in CENTRAL]
        q, limit = at + 46 + name, at + 46 + name + extra
        while q + 4 <= min(limit, len(data)):
            field_id, length = struct.unpack_from("<HH", data, q)
            out.append((q + 2, 2, f"central.extra-{field_id:04x}-length"))
            if field_id == 0x0001:
                out += [(q + 4 + i, 8, "central.zip64-value") for i in range(0, min(length, 24), 8)
                        if q + 12 + i <= len(data)]
            elif field_id == 0x9901 and length >= 7:
                out += [(q + 4, 2, "aes.version"), (q + 8, 1, "aes.strength"), (q + 9, 2, "aes.method")]
            elif field_id == 0x7075 and length >= 5:
                out += [(q + 4, 1, "unicode-path.version"), (q + 5, 4, "unicode-path.crc")]
            elif field_id == 0x5455 and length >= 5:
                out += [(q + 4, 1, "timestamp.flags"), (q + 5, 4, "timestamp.modified")]
            q += 4 + length
        local = struct.unpack_from("<I", data, at + 42)[0] + shift
        if 0 <= local <= len(data) - 30 and data[local:local + 4] == b"PK\x03\x04":
            out += [(local + o, w, what) for o, w, what in LOCAL]
            ln = struct.unpack_from("<H", data, local + 26)[0]
            if data[local + 30 + ln:local + 34 + ln] == b"\x01\x00":
                out += [(local + 34 + ln + i, 8, "local.zip64-value") for i in (0, 8)
                        if local + 42 + ln + i <= len(data)]
        at += 46 + name + extra + comment
        walked += 1
    return out


def field(data: bytes, rng: random.Random) -> tuple[bytes, str]:
    found = fields(data)
    if not found:
        return mutate(data, rng)
    out = bytearray(data)
    names = []
    for _ in range(rng.choice([1, 1, 1, 2, 3])):
        at, width, what = rng.choice(found)
        old = int.from_bytes(data[at:at + width], "little")
        top = (1 << (8 * width)) - 1
        value = rng.choice([
            rng.choice(BIG), 0, 1, old + 1, old - 1, old * 2, old + rng.randint(2, 64),
            old - rng.randint(2, 64), top, top >> 1, (top >> 1) + 1, len(data), len(data) - old,
            rng.getrandbits(8 * width),
        ]) & top
        out[at:at + width] = value.to_bytes(width, "little")
        names.append(what)
    return bytes(out), "field-" + "+".join(names)


# ---------------------------------------------------------------------------
# Archives made up on the spot, with the fixtures' writer
# ---------------------------------------------------------------------------

TEXT = b""


def lock_args(lock: str) -> dict:
    if lock == "zipcrypto":
        return {"zipcrypto": PASSWORD.encode()}
    if lock.startswith("aes"):
        return {"aes": (int(lock[3]), int(lock[4])), "password": PASSWORD.encode()}
    return {}


def made_name(rng: random.Random, shape: str, i: int) -> bytes:
    if shape == "deep":
        depth = rng.choice([100, 1000, 5000, 20000, 32000])
        segment = rng.choice([b"a", b"ab", b"\xe4", b"."])
        return (segment + b"/") * min(depth, 60000 // (len(segment) + 1)) + b"f.txt"
    if shape == "clash":
        return rng.choice([b"x", b"x/", b"x/y", b"X", b"x/y/", b"x//y", b"./x", b"x/./y", b"../x",
                           b"x\\y", b"/x", b"", b"/", b".", b"..", b"x/y/z/"])
    if shape == "long":
        return rng.randbytes(rng.randint(1000, 65000))
    if shape == "many":
        return b"f%05d/%d.txt" % (i % 97, i)
    alphabet = b"ab./\\\x00\x01\x7f\x80\xa4\xe2\x80\xae\xff "
    return bytes(rng.choice(alphabet + rng.randbytes(4)) for _ in range(rng.randint(0, 80)))


def made_extra(rng: random.Random, name: bytes) -> bytes:
    out = b""
    for _ in range(rng.choice([0, 0, 1, 1, 2])):
        kind = rng.choice(["unicode", "unicode-wrong", "timestamp", "random", "zip64"])
        if kind == "unicode":
            text = rng.choice(["Ünïcödé/名前.txt", "имя", "‮gpj.exe", "a/../b", "x" * 300]).encode()
            out += mf._extra(0x7075, b"\x01" + struct.pack("<I", mf.zlib.crc32(name)) + text)
        elif kind == "unicode-wrong":
            out += mf._extra(0x7075, rng.randbytes(rng.randint(0, 12)))
        elif kind == "timestamp":
            out += mf._extra(0x5455, bytes([rng.getrandbits(8)]) + rng.randbytes(rng.choice([0, 3, 4, 8])))
        elif kind == "zip64":
            out += mf._extra(0x0001, rng.randbytes(rng.choice([0, 4, 8, 16, 24, 28])))
        else:
            out += mf._extra(rng.getrandbits(16), rng.randbytes(rng.randint(0, 40)))
    return out


def made(rng: random.Random) -> tuple[bytes, str]:
    shape = rng.choice(["random", "random", "deep", "clash", "clash", "long", "many"])
    count = {"deep": rng.randint(1, 3), "long": rng.randint(1, 2), "many": rng.randint(500, 5000)}.get(
        shape, rng.randint(1, 12))
    members = []
    for i in range(count):
        method = rng.choice([mf.STORED, mf.DEFLATED, mf.DEFLATE64, mf.STORED, mf.DEFLATED, 12, 14, 93])
        lock = rng.choice(["none"] * 5 + ["zipcrypto", "aes11", "aes22", "aes32", "aes31"])
        if shape == "many" or method == mf.DEFLATE64:
            # The fixtures' Deflate64 encoder is plain Python, so its input stays small
            data = TEXT[:rng.randint(0, 600)]
        else:
            data = rng.choice([b"", rng.randbytes(rng.randint(1, 3000)), TEXT[:rng.randint(1, len(TEXT))]])
        name = made_name(rng, shape, i)
        members.append(mf.Member(
            name, data, method=method, descriptor=rng.random() < 0.3,
            host=rng.choice([mf.HOST_DOS, mf.HOST_UNIX, mf.HOST_UNIX, 6, 10, 14]),
            directory=rng.random() < 0.1, flags=0x800 if rng.random() < 0.2 else 0,
            extra=made_extra(rng, name) if shape != "many" else b"", time=rng.getrandbits(16),
            **(lock_args(lock) if shape != "many" else {}),
        ))
    comment = rng.choice([b"", b"", b"a comment", b"PK\x05\x06" + bytes(18), rng.randbytes(rng.randint(0, 300))])
    return mf.zip_bytes(members, zip64=rng.random() < 0.2, comment=comment), f"made-{shape}"


# ---------------------------------------------------------------------------
# Seeds, written once into <work>/corpus
# ---------------------------------------------------------------------------

def seeds(corpus: Path) -> dict[str, str]:
    """The seed archives, written once, as {file name: password}."""
    manifest = corpus / "seeds.json"
    if manifest.exists():
        return json.loads(manifest.read_text())
    corpus.mkdir(parents=True, exist_ok=True)
    passwords = {}
    for p in sorted(FIXTURES.iterdir()):
        if p.suffix.lower() in ARCHIVES:
            shutil.copy(p, corpus / p.name)
            passwords[p.name] = "пароль" if "cyrillic" in p.name else PASSWORD

    rng = random.Random(0)
    noise = rng.randbytes(3000)
    for method in (mf.STORED, mf.DEFLATED, mf.DEFLATE64):
        for lock in ("none", "zipcrypto", "aes11", "aes22", "aes32"):
            for descriptor in (False, True):
                for zip64 in (False, True):
                    members = [
                        mf.Member(b"folder/", method=mf.STORED, directory=True),
                        mf.Member(b"folder/text.md", TEXT[:1500], method=method, descriptor=descriptor,
                                  time=0x7340, **lock_args(lock)),
                        mf.Member(b"noise.bin", noise, method=method, descriptor=descriptor,
                                  time=0x7340, **lock_args(lock)),
                        mf.Member(b"empty.txt", b"", method=method, **lock_args(lock)),
                    ]
                    name = f"gen-m{method}-{lock}-{'d' if descriptor else 'n'}-{'64' if zip64 else '32'}.zip"
                    (corpus / name).write_bytes(mf.zip_bytes(members, zip64=zip64, comment=b"seed"))
                    passwords[name] = PASSWORD

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "text.txt").write_bytes(TEXT * 20)
        (tmp / "noise.bin").write_bytes(rng.randbytes(60000))
        (tmp / "pattern.bin").write_bytes(b"ab" * 40000 + TEXT[:3000] + b"ab" * 40000)
        shutil.copy(FIXTURES / "six-pages.pdf", tmp / "six-pages.pdf")
        inputs = ["text.txt", "noise.bin", "pattern.bin", "six-pages.pdf"]
        seven = shutil.which("7zz") or shutil.which("7z")
        if seven:
            for label, flags in [
                ("7z-deflate64", ["-mm=Deflate64", "-mx=9"]),
                ("7z-deflate", ["-mm=Deflate"]),
                ("7z-stored", ["-mx=0"]),
                ("7z-aes128", ["-mem=AES128", f"-p{PASSWORD}"]),
                ("7z-aes192-deflate64", ["-mem=AES192", "-mm=Deflate64", f"-p{PASSWORD}"]),
                ("7z-aes256-stored", ["-mem=AES256", "-mx=0", f"-p{PASSWORD}"]),
                ("7z-zipcrypto", ["-mem=ZipCrypto", f"-p{PASSWORD}"]),
                ("7z-zipcrypto-stored", ["-mem=ZipCrypto", "-mx=0", f"-p{PASSWORD}"]),
            ]:
                out = corpus / f"{label}.zip"
                subprocess.run([seven, "a", "-tzip", "-bso0", "-bsp0", *flags, str(out), *inputs],
                               cwd=tmp, check=True)
                passwords[out.name] = PASSWORD
        if shutil.which("zip"):
            for label, args, stdin in [
                ("infozip", ["-X", "-q"], None),
                ("infozip-zipcrypto", ["-X", "-q", "-P", PASSWORD], None),
                ("infozip-zip64", ["-X", "-q", "-fz"], None),
                ("infozip-stored", ["-X", "-q", "-0"], None),
            ]:
                out = corpus / f"{label}.zip"
                subprocess.run(["zip", *args, str(out), *inputs], cwd=tmp, check=True, input=stdin)
                passwords[out.name] = PASSWORD
            # Read from a pipe: sizes after the data, and ZIP64 records the end record does not mark
            out = corpus / "infozip-pipe.zip"
            with open(tmp / "text.txt", "rb") as piped, open(out, "wb") as written:
                subprocess.run(["zip", "-q", "-", "-"], stdin=piped, stdout=written, check=True)
            passwords[out.name] = PASSWORD
            # Something in front, as a self-extractor has, with the offsets adjusted and not
            plain = (corpus / "infozip.zip").read_bytes()
            (corpus / "prefixed-raw.zip").write_bytes(b"MZ" + rng.randbytes(3000) + plain)
            passwords["prefixed-raw.zip"] = PASSWORD
            adjusted = tmp / "adjusted.zip"
            adjusted.write_bytes(b"MZ" + rng.randbytes(3000) + plain)
            subprocess.run(["zip", "-q", "-A", str(adjusted)], check=True)
            shutil.copy(adjusted, corpus / "prefixed-adjusted.zip")
            passwords["prefixed-adjusted.zip"] = PASSWORD

    # Raw Deflate64 streams, cut out of every seed that has one, and a few written here
    for name in list(passwords):
        for i, raw in enumerate(raw_deflate64(corpus / name)):
            (corpus / f"{Path(name).stem}-{i}.d64").write_bytes(raw)
    for label, data in [("text", TEXT * 4), ("pattern", b"ab" * 20000 + TEXT[:2000]),
                        ("noise", rng.randbytes(20000)), ("empty", b"")]:
        (corpus / f"made-{label}.d64").write_bytes(mf.deflate64(data))
    manifest.write_text(json.dumps(passwords, indent=1, ensure_ascii=False))
    return passwords


def raw_deflate64(path: Path) -> list[bytes]:
    """The raw data of each unencrypted Deflate64 entry in the archive at [path]."""
    out = []
    data = path.read_bytes()
    try:
        entries = zipfile.ZipFile(path).infolist()
    except (zipfile.BadZipFile, OSError, ValueError):
        return out
    for e in entries:
        if e.compress_type != 9 or e.flag_bits & 1:
            continue
        at = e.header_offset
        if data[at:at + 4] != b"PK\x03\x04":
            continue
        name, extra = struct.unpack_from("<HH", data, at + 26)
        start = at + 30 + name + extra
        out.append(data[start:start + e.compress_size])
    return out


# ---------------------------------------------------------------------------
# The harness, kept running, and started again whenever a case hangs or kills it
# ---------------------------------------------------------------------------

class Harness:
    def __init__(self, err):
        lines = subprocess.run(
            [str(REPO / "gradlew"), "-q", "-I", str(HERE / "classpath.gradle"), ":app:zipFuzzClasspath"],
            cwd=REPO, check=True, capture_output=True, text=True,
        ).stdout.strip().splitlines()
        java, classpath = lines[-2], lines[-1]
        # A phone's heap, near enough: 256 MB is what a mid-range one gives an app
        self.command = [java, "-Xmx256m", "-cp", classpath, "com.arjun.gander.ZipFuzz"]
        self.err = err
        self.start()

    def start(self):
        self.process = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.err, text=True, encoding="utf-8", bufsize=1)
        self.lines = queue.Queue()
        threading.Thread(target=self.pump, args=(self.process, self.lines), daemon=True).start()

    @staticmethod
    def pump(process, lines):
        for line in process.stdout:
            lines.put(line.rstrip("\n"))
        lines.put(None)

    def restart(self):
        self.process.kill()
        self.process.wait()
        self.start()

    def ask(self, case: str, timeout: float) -> tuple[str, str]:
        t0 = time.time()
        try:
            self.process.stdin.write(case + "\n")
            self.process.stdin.flush()
            line = self.lines.get(timeout=timeout)
        except queue.Empty:
            self.restart()
            return "hang", f"{time.time() - t0:.1f}s"
        except (BrokenPipeError, OSError) as e:
            self.restart()
            return "crash", str(e)[:120]
        if line is None:
            code = self.process.wait()
            self.restart()
            return "crash", f"harness exited {code}"
        outcome, _, detail = line.partition("\t")
        return outcome, detail

    def close(self):
        self.process.stdin.close()
        self.process.wait()


def case_line(mode: str, path: Path, password: str, i: int, rng: random.Random) -> str:
    if mode == "deflate64":
        return f"deflate64\t{path}"
    # Now and then the wrong password, and a code page picked by hand rather than guessed
    given = password if rng.random() < 0.85 else rng.choice(["", "wrong", "Gander", "пароль"])
    chosen = rng.choice(["-"] * 6 + ["utf8", "gbk", "sjis", "korean", "cp866", "cp437"])
    return f"zip\t{path}\t{given}\t{i}\t{chosen}"


def main():
    global TEXT
    ap = argparse.ArgumentParser()
    ap.add_argument("work")
    ap.add_argument("--count", type=int, default=6000)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--timeout", type=float, default=10)
    ap.add_argument("--only", default="", help="seeds whose name holds this")
    ap.add_argument("--modes", default=",".join(MODES), help="of " + ", ".join(MODES))
    ap.add_argument("--replay", help="a kept case to read again, printing its verdict")
    args = ap.parse_args()
    root = Path(args.work).resolve()
    TEXT = (FIXTURES / "notes.md").read_bytes() + (FIXTURES / "plain.txt").read_bytes()
    passwords = seeds(root / "corpus")

    fuzz_dir = root / "fuzz"
    found_dir = fuzz_dir / "found"
    current = fuzz_dir / "current"
    for d in (fuzz_dir, found_dir, current):
        d.mkdir(parents=True, exist_ok=True)
    err = open(fuzz_dir / "harness.err", "a")
    harness = Harness(err)

    if args.replay:
        path = Path(args.replay).resolve()
        if path.suffix == ".names":
            line = f"names\t{path.read_text().strip()}"
        elif path.suffix == ".d64":
            line = f"deflate64\t{path}"
        else:
            line = f"zip\t{path}\t{PASSWORD}\t0\t-"
        print(*harness.ask(line, args.timeout), sep="\t")
        harness.close()
        return

    corpus = root / "corpus"
    archives = [corpus / n for n in sorted(passwords) if args.only in n]
    streams = [p for p in sorted(corpus.glob("*.d64")) if args.only in p.name]
    modes = [m for m in args.modes.split(",") if m in MODES]
    weights = [MODES[m] for m in modes]
    if not archives:
        raise SystemExit("no seeds")
    rng = random.Random(args.seed)

    tally = {k: 0 for k in ("read", "refused", "escaped", "invariant", "memory", "hang", "crash")}
    findings = []
    t_start = time.time()
    for i in range(args.count):
        mode = rng.choices(modes, weights)[0]
        if mode == "deflate64" and not streams:
            mode = "bytes"
        if mode == "names":
            src, kind, data, suffix = "names", "names", str(rng.getrandbits(48)).encode(), ".names"
            line = f"names\t{data.decode()}"
        else:
            if mode == "deflate64":
                src_path = rng.choice(streams)
                data, kind = mutate(src_path.read_bytes(), rng)
                kind = "d64-" + kind
            elif mode == "made":
                src_path = None
                data, kind = made(rng)
            else:
                src_path = rng.choice(archives)
                data, kind = (field if mode == "field" else mutate)(src_path.read_bytes(), rng)
            src = src_path.name if src_path else "made"
            suffix = ".d64" if mode == "deflate64" else ".zip"
            case = current / ("case" + suffix)
            case.write_bytes(data)
            line = case_line(mode, case, passwords.get(src, PASSWORD), i, rng)
        outcome, detail = harness.ask(line, args.timeout)
        tally[outcome] = tally.get(outcome, 0) + 1
        if outcome not in ("read", "refused"):
            safe = re.sub(r"[^A-Za-z0-9._+-]", "_", kind)[:60]
            kept = found_dir / f"{i:05d}-{safe}-{Path(src).stem}{suffix}"
            kept.write_bytes(data)
            findings.append({"file": kept.name, "outcome": outcome, "detail": detail, "source": src,
                             "case": line.replace(str(current / ("case" + suffix)), str(kept))})
            print(f"  {outcome:9} {detail[:100]:100} {kept.name}", flush=True)
        if (i + 1) % 250 == 0:
            print(f"{i + 1}/{args.count}  {tally}  {time.time() - t_start:.0f}s", flush=True)
    harness.close()

    # The same bug found by a hundred cases is one bug: sort by what was thrown and where
    kinds = {}
    for f in findings:
        key = f["outcome"] + " " + re.sub(r"\d+", "N", f["detail"])[:140]
        kinds.setdefault(key, []).append(f["file"])
    (fuzz_dir / "results.json").write_text(json.dumps(
        {"tally": tally, "kinds": {k: len(v) for k, v in kinds.items()}, "findings": findings}, indent=1))
    print("done:", tally)
    for k, files in sorted(kinds.items(), key=lambda kv: -len(kv[1])):
        print(f"  {len(files):5}  {k}   e.g. {files[0]}")
    print(f"{len(findings)} kept under {found_dir}")


if __name__ == "__main__":
    main()
