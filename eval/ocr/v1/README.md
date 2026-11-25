# OCR evaluation set `ocr/v1` (P2-04)

Synthetic, generated images with ground truth. Nothing here is user data.
The set is packaged only into `:vault`'s androidTest APK (`assets/v1/`);
`scripts/check-no-eval-fixtures.sh` fails any APK that carries it, and
`scripts/inspect-release-apk.sh` runs that check on every release APK.

## Contents

| Category | Count | Style | Notes |
| --- | --- | --- | --- |
| screenshot | 15 | 1080x2400 RGB phone screens: chats, emails, appointments | `screenshot-07` fully Cyrillic; `screenshot-14` Latin email with a Cyrillic signature |
| receipt | 15 | 576 px wide grayscale thermal receipts, IBM Plex Mono, skew -3..+3 degrees, salt-and-pepper noise 0.2-0.5% | `receipt-07` one stray Cyrillic word; `receipt-12` one stray glyph (Ω); currency signs €, £, ¥ on Latin receipts |
| letter | 10 | 1240x1754 grayscale letters and forms | `letter-08` fully Greek |

Five fixtures carry non-Latin text and have `expected_abstain: true`; the
other 35 are Latin-only.

- `fixtures/<id>.png`: the image.
- `truth/<id>.json`: every line's text, its ink bounding box
  `[left, top, right, bottom]` (right and bottom exclusive, after skew), and
  its script.
- `manifest.json`: seed, generator version, JDK, font digests, and the
  SHA-256 of each PNG, of its decoded pixels (`raster_sha256`) and of its
  truth file.
- `gate.json`: the thresholds (P2-04-R3). Changing one is a reviewed diff
  here and in `eval/src/test/.../GateFileTest.kt` (R4).

## Fonts

The images use IBM Plex Sans, Sans SemiBold and Mono from
`app/src/main/res/font`. Those files are Latin-only subsets, so Cyrillic and
Greek are drawn with Liberation Sans 2.1.5 (SIL OFL 1.1, `eval/fonts/`).
The generator fails rather than draw a character neither font has. No CJK
fixture exists, because no vendored font covers CJK.

## Regenerating and checking

```bash
./gradlew :eval:generateOcrFixtures   # rewrites this directory from the seed
./gradlew :eval:test                  # committedSetMatchesTheGenerator compares pixels and truth with the committed files
```

Generation is deterministic for a given JDK. The test compares decoded
pixels rather than PNG bytes, so a different zlib cannot fail it.

## Metrics (`eval/src/core/kotlin/.../OcrMetrics.kt`)

- **CER**: the Levenshtein distance over code points divided by the
  reference length.
  - The reference is the truth lines joined by newlines.
  - The hypothesis is the engine spans, grouped into rows by vertical centre
    and ordered left to right.
  - Both are NFC-normalised, with whitespace collapsed.
  - An abstention or failure scores CER 1.0.
- **Line match rate**: matched truth lines divided by all truth lines over
  the Latin fixtures.
  - A truth line matches at most one span, greedily by highest IoU, and only
    when IoU is at least `line_match_iou` (0.5).
- **Abstention correctness**: the share of `expected_abstain` fixtures on
  which production abstained.
- **Duration p95**: nearest-rank p95 of the time production `extract` takes
  per image, after one warm-up.
- **Peak PSS delta**: the maximum over images of peak minus baseline
  `Debug.getPss`, sampled every 50 ms, in KiB.
  - The limit is 120 MiB (122880 KiB).

## Gate outcomes (`OcrGate.kt`)

| Outcome | When |
| --- | --- |
| `PASS` | Every check passes. This is the only outcome that permits an engine to be the default. |
| `FAIL` | Any check misses its threshold. FAIL outranks NOT_MEASURED. |
| `NO_MEASUREMENT` | There is no results file, or a check has nothing to measure. Fails closed. |
| `INVALID` | The results file is malformed, belongs to another set or gate, or has missing, duplicate or unknown fixtures. |

## Device run (pending)

These commands run only on a physical device. No device run has happened
yet.

```bash
./gradlew :vault:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.eval.OcrEvaluationTest \
  -Pandroid.testInstrumentationRunnerArguments.p204Eval=true
# then pull results-<engine>-<date>.json from the test output and:
./gradlew :eval:checkOcrGate -PocrResults=<path>/results-tesseract-eng-fast-<date>.json,<path>/results-mlkit-latin-<date>.json
```
