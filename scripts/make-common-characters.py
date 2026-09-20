#!/usr/bin/env python3
"""
Writes CommonCharacters.kt: the syllables and characters Korean and Chinese are mostly
written in, which ZipNames uses to tell a Korean zip's names from a Chinese one's. #30.

The two share byte ranges exactly: every Hangul syllable in a Korean Windows zip is also a
valid, common-looking Chinese character in GBK, so nothing about the bytes separates them.
What does is that the Korean reading comes out in the syllables Korean is written in, and
the Chinese reading of the same bytes comes out in characters Chinese rarely uses.

Counted over macOS's own Korean, Simplified and Traditional Chinese interface text, each
distinct string once, so a word repeated across a thousand buttons counts as one use. Only
the ranking is kept. It needs a Mac, and a different macOS release will rank a few of the
rarer characters differently; the choice of code page does not hang on any one of them.

The list lengths and the bonus in ZipNames were chosen on the half of that text this script
does not rank from, and on a list of words people name files with, measuring how often a
zip of one, three and eight names comes out in the wrong code page on a phone set to each
language. See ZipNames.COMMON_BONUS.

Usage:  python3 scripts/make-common-characters.py
"""

import os
import plistlib
import re
from collections import Counter
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/java/com/arjun/gander/CommonCharacters.kt"
ROOTS = ["/System/Library", "/Applications"]
LANGUAGES = {
    "KOREAN": (("ko",), lambda c: 0xAC00 <= ord(c) <= 0xD7A3, 500),
    "SIMPLIFIED": (("zh_CN", "zh-Hans"), lambda c: 0x4E00 <= ord(c) <= 0x9FFF, 800),
    "TRADITIONAL": (("zh_TW", "zh-Hant"), lambda c: 0x4E00 <= ord(c) <= 0x9FFF, 800),
}


def values(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from values(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from values(v)


def strings_in(path):
    data = path.read_bytes()
    try:
        return list(values(plistlib.loads(data)))
    except Exception:
        pass
    for encoding in ("utf-16", "utf-8"):
        try:
            return re.findall(r'=\s*"((?:[^"\\]|\\.)*)"\s*;', data.decode(encoding))
        except UnicodeDecodeError:
            continue
    return []


def corpus():
    found = {name: set() for name in LANGUAGES}
    for root in ROOTS:
        for dirpath, _, filenames in os.walk(root):
            here = Path(dirpath)
            for name, (folders, _, _) in LANGUAGES.items():
                if here.name in {f + ".lproj" for f in folders}:
                    for f in filenames:
                        if f.endswith((".strings", ".stringsdict")):
                            try:
                                found[name].update(strings_in(here / f))
                            except OSError:
                                pass
            for f in filenames:
                if not f.endswith(".loctable"):
                    continue
                try:
                    table = plistlib.loads((here / f).read_bytes())
                except Exception:
                    continue
                for name, (folders, _, _) in LANGUAGES.items():
                    for folder in folders:
                        if folder in table:
                            found[name].update(values(table[folder]))
    return found


def main() -> int:
    texts = corpus()
    lists = {}
    for name, (_, belongs, count) in LANGUAGES.items():
        counts = Counter(c for s in texts[name] for c in s if belongs(c))
        ranked = sorted(counts, key=lambda c: (-counts[c], ord(c)))
        lists[name] = "".join(ranked[:count])
        print(f"{name}: {len(texts[name]):,} strings, {len(counts):,} distinct, kept {len(lists[name])}")

    def wrapped(text, width=40):
        return "\n".join(f'        "{text[i:i + width]}" +' for i in range(0, len(text), width))[:-2]

    OUT.write_text(f'''package com.arjun.gander

/**
 * The Hangul syllables and hanzi that Korean and Chinese are mostly written in, commonest
 * first. What ZipNames tells a Korean zip's names from a Chinese one's by. Issue #30.
 *
 * Written by scripts/make-common-characters.py, which says where the ranking comes from and
 * how long each list is and why. Regenerate rather than edit.
 */
internal object CommonCharacters {{

    /** The {len(lists["KOREAN"])} commonest Hangul syllables. */
    const val KOREAN =
{wrapped(lists["KOREAN"])}

    /** The {len(lists["SIMPLIFIED"])} commonest hanzi in Simplified Chinese. */
    const val SIMPLIFIED =
{wrapped(lists["SIMPLIFIED"])}

    /** The {len(lists["TRADITIONAL"])} commonest hanzi in Traditional Chinese. */
    const val TRADITIONAL =
{wrapped(lists["TRADITIONAL"])}
}}
''')
    print(f"Wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
