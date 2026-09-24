# Fuzzing the zip reader

`fuzz.py` does for `ZipReader`, `Deflate64`, `ZipEncryption` and `ZipNames` what
`scripts/prose-corpus/fuzz.py` does for the prose readers: it breaks archives, and makes
some up, and reads each one the way Gander would, with a timeout. The reading is done by
`app/src/test/.../ZipFuzz.kt`, compiled with the unit tests so it can see what `internal`
hides; `classpath.gradle` hands `fuzz.py` the classpath to start it on. Needs the Python the
fixtures use, with `cryptography` for the AES seeds. 7-Zip (`7zz`) and Info-ZIP's `zip` add
seeds of their own when installed.

```sh
W=/tmp/zip-fuzz                                    # anywhere outside the repo
python3 scripts/zip-fuzz/fuzz.py "$W" --count 20000 --seed 11
python3 scripts/zip-fuzz/fuzz.py "$W" --replay "$W/fuzz/found/<file>"
```

Read and refused are the two acceptable answers. Anything else thrown, a path or size the
app is promised never to see, more than 256 MB allocated, a hang or a crash is a bug, and the
case that found it is kept under `$W/fuzz/found/`.

Break the reader before trusting a clean run: a planted endless loop in `Deflate64` and a
planted exception in `ZipReader.index` both showed up within 600 cases on 2026-09-23. That
night 60,000 cases over three seeds found one bug, a name 30,000 folders deep that sent the
folder tree after two gigabytes, and nothing else once it was fixed. All 90 seeds, the ones
7-Zip and Info-ZIP wrote among them, read to the end unbroken.
