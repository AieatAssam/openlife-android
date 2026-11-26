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
- `truth/<id>.json` (schema `openlife-ocr-truth/2`): for every line, its
  text, its ink bounding box `[left, top, right, bottom]` (right and bottom
  exclusive, after skew), its script, and its `segments`.
  - A segment is a column of the line. Columns are separated by a run of
    3 or more spaces. Each segment has its own text, ink box and script.
  - Most lines have one segment, equal to the line.
  - 167 receipt item and price rows have two segments. 144 of them are on
    Latin fixtures and 23 on the two receipts that are expected to
    abstain.
- `manifest.json`: seed, generator version (2), the runtime that rendered
  the set (`rendered_with`: `java.runtime.version`, `java.vm.vendor`, OS
  and architecture), font digests, and the SHA-256 of each PNG, of its
  decoded pixels (`raster_sha256`) and of its truth file.
- `gate.json` (schema `openlife-ocr-gate/2`): the thresholds (P2-04-R3)
  and the engines with their pinned model versions. Changing either is a
  reviewed diff here and in `eval/src/test/.../GateFileTest.kt` (R4).
  - The pins are `TesseractOcrEngine.MODEL_VERSION` and ML Kit's
    `16.0.1` (`gradle/libs.versions.toml`).
  - `vault/src/test/.../GatePinsTest.kt` fails if production changes a
    model without changing the gate.

## Fonts

The images use IBM Plex Sans, Sans SemiBold and Mono from
`app/src/main/res/font`. Those files are Latin-only subsets, so Cyrillic and
Greek are drawn with Liberation Sans 2.1.5 (SIL OFL 1.1, `eval/fonts/`).
The generator fails rather than draw a character neither font has. No CJK
fixture exists, because no vendored font covers CJK.

## Regenerating and checking

```bash
./gradlew :eval:generateOcrFixtures   # rewrites this directory from the seed
./gradlew :eval:test                  # also runs in CI's build job
```

Two tests in `FixtureGeneratorTest` check the set.

- `committedSetMatchesItsManifest` always runs. It checks every PNG and
  truth file against the manifest digests, and that every PNG decodes to
  the recorded pixels.
- `committedSetRegeneratesOnTheRecordingRuntime` regenerates the set and
  compares decoded pixels and truth JSON.
  - Font rasterisation can differ between JDK builds, so this test runs
    only when the current runtime equals `rendered_with`.
  - On any other runtime, JUnit reports it as skipped, with both runtimes
    in the message. It is never reported as passed.
  - The set was rendered with `21.0.13.1+2-nixos` (Oracle Corporation VM,
    Linux amd64). CI's Temurin 21 therefore skips this test.

## Metrics (`eval/src/core/kotlin/.../OcrMetrics.kt`)

- **CER**: the Levenshtein distance over code points divided by the
  reference length.
  - The reference is the whole truth lines joined by newlines. Segments do
    not change the reference.
  - The hypothesis is the engine spans, grouped into rows and ordered left
    to right (reading order, below).
  - Both are NFC-normalised, with whitespace collapsed.
  - An abstention, failure or timeout scores CER 1.0.
- **Reading order**: each span's box centre is rotated back by the
  fixture's recorded `skew_degrees` about the image centre.
  - A span joins the current row when its deskewed centre is within half
    a text height of the row's first span.
  - The text height comes from the first span's box, corrected for the
    rotation.
  - Only the image's own geometry is used: the skew the generator applied
    and the image size. No truth text or truth box is used.
  - Limits: a real photo has no recorded skew; curved or perspective-warped
    rows are not straightened; rows closer than half a text height can
    still merge.
- **Line match rate**: matched truth lines divided by all truth lines over
  the Latin fixtures.
  - Each truth line offers its whole-line box. A line with 2 or more
    segments also offers each segment's box.
  - Spans and boxes are paired one to one, greedily by highest IoU, only
    when IoU is at least `line_match_iou` (0.5).
  - Pairing a line's whole box withdraws its segment boxes, and pairing a
    segment withdraws its whole box. A span is therefore never counted
    twice for one line.
  - A line is matched when its whole box is paired, or when every one of
    its segments is paired.
  - One span over a whole receipt row and one span per column both match
    the row. Only the item, without the price, does not.
- **Abstention correctness**: the share of `expected_abstain` fixtures on
  which production abstained.
- **Duration p95**: nearest-rank p95 of the time production `extract` takes
  per image.
  - One unmeasured warm-up call per engine comes first. It installs and
    digests tessdata and warms ML Kit and the JIT.
  - Production Tesseract opens its model in every `extract`, so every
    measured duration includes that model load.
  - Each call has a 60 s limit. A call that has not returned is recorded
    as `timed_out`.
- **Peak PSS delta**: the maximum over images of peak minus baseline
  `Debug.getPss`, sampled every 50 ms, in KiB.
  - The limit is 120 MiB (122880 KiB).

## Gate outcomes (`OcrGate.kt`)

| Outcome | When |
| --- | --- |
| `PASS` | Every check passes. This is the only outcome that permits an engine to be the default. |
| `FAIL` | Any check misses its threshold. FAIL outranks NOT_MEASURED. |
| `NO_MEASUREMENT` | There is no results file, or a check has nothing to measure. Fails closed. |
| `INVALID` | The results cannot be trusted (see below). |

Results are INVALID when any of these holds:

- The file is malformed, or belongs to another set or gate.
- The file has missing, duplicate or unknown fixtures.
- The engine id is not pinned in `gate.json`.
- The model version differs from the gate's pin ("stale").
- `code_commit` is not a full 40-character commit.
- The device's manufacturer, model, SDK or ABI is missing.
- Spans are present on a result that is not `output`.
- A recorded score disagrees with the spans.
  - The gate recomputes every fixture's CER and matched-line count from
    the recorded spans and the committed truth.
  - CER may differ by at most `1e-9`. The matched-line count must be equal.
  - `lines_total` must equal the truth's line count.
- Two results files name the same engine ("duplicate results").

The gate has 9 checks: the three per-category CER means, the line match
rate, abstention correctness, `abstentions_on_latin`, `timeouts`, duration
p95, and peak PSS delta.

- `abstentions_on_latin` counts the Latin fixtures on which production
  abstained. The limit is 0.
  - This is an acting-owner ruling (2026-11-25), and it is stricter than
    R3's wording.
  - Under R3's wording, an abstention only raised the category's CER mean.
    One abstention among 13 or 14 Latin fixtures stayed under the mean
    limit and passed.
- `timeouts` counts the `timed_out` results. The limit is 0.
  - This check was added in the same review for the same reason. It is
    not yet owner-reviewed.

`./gradlew :eval:checkOcrGate` exits with a status for the worst required
engine. The order is INVALID, then FAIL, then NO_MEASUREMENT.

| Exit | Meaning |
| --- | --- |
| 0 | PASS |
| 1 | FAIL |
| 2 | NO_MEASUREMENT |
| 3 | INVALID, including a missing results file or an unknown `--require` engine |
| 64 | Usage error |

The R5 policy numbers in a results file are reported but not recomputed,
because the production script classifier is not part of `:eval`.

## Device run (pending)

These commands run only on a physical device. No device run has happened
yet.

```bash
./gradlew :vault:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.eval.OcrEvaluationTest \
  -Pandroid.testInstrumentationRunnerArguments.p204Eval=true \
  -Pandroid.testInstrumentationRunnerArguments.p204Commit=$(git rev-parse HEAD)
./gradlew :eval:checkOcrGate -PocrResults=<path>/results-tesseract-eng-fast-<date>.json,<path>/results-mlkit-latin-<date>.json
```

The results files land in AGP's additional test output,
`vault/build/outputs/connected_android_test_additional_output/`. If they
are not there, the harness has written them to the test package's
external files directory under `ocr-eval/`. Retrieve them with
`adb pull /sdcard/Android/data/<test package>/files/ocr-eval`, which
needs no root.

Logcat tag `P204Eval` carries a second copy of the results. It is split
into entries of at most 3000 UTF-8 bytes. Only synthetic text may ever be
logged there: this committed set and the engines' readings of it.
