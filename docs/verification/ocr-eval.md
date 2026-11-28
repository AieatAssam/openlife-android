# OCR evaluation: results summary (P2-04)

Set: `eval/ocr/v1` (40 synthetic fixtures; see `eval/ocr/v1/README.md`).
Gate: `eval/ocr/v1/gate.json`. Verification log:
`docs/verification/P2-04-ocr-evaluation.md`.

**Status, 2026-11-25: no engine has been measured.** Every engine figure
below is a gap pending a physical-device run. No number is estimated.

## Gate outcome

`./gradlew :eval:checkOcrGate` was run on this host on 2026-11-25 with no
results files. It exited 1:

```text
GATE tesseract-eng-fast: NO_MEASUREMENT (no results file); permits default: false
GATE mlkit-latin: NO_MEASUREMENT (no results file); permits default: false
OCR gate not passed: [tesseract-eng-fast=NO_MEASUREMENT, mlkit-latin=NO_MEASUREMENT]
```

Neither engine may be selected as the default on the strength of this gate.

The gate checks the provenance of a results file before scoring it.

- The engine's model version must equal the pin in `gate.json`. The pins
  are `t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd` for Tesseract and `16.0.1`
  for ML Kit.
- The file must name the code commit and the device.
- Each fixture's CER and matched lines are recomputed from the recorded
  spans. CER may differ by at most 1e-9, and the line count must be equal.
- A results file that fails any of these is INVALID and is never scored.

**Stricter than R3.** By acting-owner ruling on 2026-11-25, one abstention
on a Latin fixture fails the engine.

- R3 averaged such an abstention into the category's CER mean as 1.0.
- On this set, one wrong abstention among 13 or 14 Latin fixtures still
  passed under that rule.

| Check | Limit | tesseract-eng-fast | mlkit-latin |
| --- | --- | --- | --- |
| Mean CER, screenshots | <= 0.08 | gap — pending physical-device run | gap — pending physical-device run |
| Mean CER, receipts | <= 0.15 | gap — pending physical-device run | gap — pending physical-device run |
| Mean CER, letters | <= 0.10 | gap — pending physical-device run | gap — pending physical-device run |
| Lines matched at IoU >= 0.5 | >= 0.90 | gap — pending physical-device run | gap — pending physical-device run |
| Correct abstention, non-Latin fixtures | 1.0 | gap — pending physical-device run | gap — pending physical-device run |
| Abstentions on Latin fixtures (acting-owner ruling, X-03) | 0 | gap — pending physical-device run | gap — pending physical-device run |
| Timed-out images (60 s each; added in review, not owner-reviewed) | 0 | gap — pending physical-device run | gap — pending physical-device run |
| p95 duration | <= 6000 ms | gap — pending physical-device run | gap — pending physical-device run |
| Peak PSS delta | <= 122880 KiB (120 MiB) | gap — pending physical-device run | gap — pending physical-device run |

## V-15: whole-revision versus per-span script policy (R5)

**Measured on the truth only (JVM, 2026-11-25).**
`vault/src/test/.../StrayGlyphPolicyOnTruthTest` feeds each fixture's
truth lines, standing in for a perfect recogniser, through the production
`OcrScriptPolicy` and `OcrRunStateMachine.decide`. It then compares that
result with the per-span alternative, which is evaluated offline by
`ScriptPolicyCost`.

The table shows only the five non-Latin or mixed fixtures. On the 35 Latin
fixtures, both policies lose 0 lines and accept 0 non-Latin lines. The
currency signs (€, £, ¥) do not cause an abstention.

| Fixture | Class | Latin lines | Non-Latin lines | Whole revision: Latin lost / non-Latin accepted | Per span: Latin lost / non-Latin accepted |
| --- | --- | --- | --- | --- | --- |
| screenshot-07 | non_latin | 1 | 10 | 1 / 0 | 0 / 0 |
| screenshot-14 | mixed | 14 | 1 | 14 / 0 | 0 / 0 |
| receipt-07 | mixed | 18 | 1 | 18 / 0 | 0 / 0 |
| receipt-12 | mixed | 16 | 1 | 16 / 0 | 0 / 0 |
| letter-08 | non_latin | 1 | 9 | 1 / 0 | 0 / 0 |

On the truth, one stray Cyrillic word or Ω glyph makes the whole-revision
policy discard every Latin line on that image: 14 to 18 lines on each mixed
fixture. The per-span policy keeps them and accepts no non-Latin text.

This is the cost to an ideal recogniser. A real engine adds its own errors:
an engine may misread the stray glyph, or a Latin-only model may turn it into
Latin letters, which would hide the abstention. The harness measures both
policies on each engine's raw spans, and those figures are a gap pending a
physical-device run.

**Decision: deferred.** R5 asks for a decision made with the engine data,
and that data does not exist yet. The production policy is unchanged:
whole-revision abstention, matching the C1 wording. No ADR-0003 addendum has
been opened. The decision is recorded once the device results exist.

## Pending device commands

These need tablet `U8BBB20806201256`, when no other agent is using it:

```bash
./gradlew :vault:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.eval.OcrEvaluationTest \
  -Pandroid.testInstrumentationRunnerArguments.p204Eval=true \
  -Pandroid.testInstrumentationRunnerArguments.p204Commit=$(git rev-parse HEAD)
./gradlew :eval:checkOcrGate \
  -PocrResults=<dir>/results-tesseract-eng-fast-<date>.json,<dir>/results-mlkit-latin-<date>.json
```

The harness writes `results-<engine>-<date>.json` to the
`additionalTestOutputDir` instrumentation argument. AGP copies it to
`vault/build/outputs/connected_android_test_additional_output/`.

- If that argument is absent, the harness writes to the test package's
  external files directory under `ocr-eval/`. Retrieve that with
  `adb pull`; no root is needed.
- It also logs the JSON under the logcat tag `P204Eval`, in entries of at
  most 3000 UTF-8 bytes. Only synthetic fixture text may be logged there.
- Before the first run, read the checklist in
  `docs/verification/P2-04-ocr-evaluation.md`.
- The review suspects that the English-only models cannot emit Cyrillic or
  Greek. If so, the abstention check would read 0/5 by construction.

## Tablet run 2026-11-28 (Huawei AGS2-W09, Android 8.0, API 26)

Supersedes the 2026-11-25 status above for the gate: both engines now have
measured results on a physical device. The 2026-11-25 tables are kept
unchanged; their "gap" entries describe that date only.

- Device: Huawei AGS2-W09 (`ro.build.version.sdk=26`, arm64-v8a), adb serial
  `U8BBB20806201256`. No emulator was used.
- Code: commit `df98bb3bd334a614f0a80f9af7b7b2f82b446455` (branch
  `verify/ocr-eval-tablet` from `main`). No production code changed.
- Set and gate: `manifest_sha256`
  `73a4dc901e5c2fc874560d2a7f86d98867290ee5adeebea2cdbd60c1696e9e41`,
  `gate_sha256` `a1fe9421c5422a949575b883673bae9a44938183f8181c6bf318bf0e3536692d`.
  Both are the values recorded in the 2026-11-27 run.
- Model pins (checked by the gate): `tesseract-eng-fast`
  `t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd`; `mlkit-latin` `16.0.1`.

### Commands

```bash
export ANDROID_SERIAL=U8BBB20806201256
./gradlew :vault:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.eval.OcrEvaluationTest \
  -Pandroid.testInstrumentationRunnerArguments.p204Eval=true \
  -Pandroid.testInstrumentationRunnerArguments.p204Commit=df98bb3bd334a614f0a80f9af7b7b2f82b446455
./gradlew :vault:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.OcrEngineMeasurementTest \
  -Pandroid.testInstrumentationRunnerArguments.p203Measure=true
./gradlew :eval:checkOcrGate \
  -PocrResults=<dir>/results-tesseract-eng-fast-2026-11-28.json,<dir>/results-mlkit-latin-2026-11-28.json
```

Both `connectedDebugAndroidTest` runs: `BUILD SUCCESSFUL`, JUnit
`tests=1 failures=0 errors=0 skipped=0`. Each run wrote its results to
`vault/build/outputs/connected_android_test_additional_output/`, and both JSON
files were copied from there. Raw reports are in the scratchpad at
`device-logs/2026-11-28-ocr-eval/` (results JSON, gate output, JUnit XML,
Gradle logs).

### Gate results (`:eval:checkOcrGate`, exit 1)

Category CER means use the Latin fixtures only (`OcrGate.kt`, the
`expectedAbstain` filter), so the five non-Latin or mixed fixtures count
only through the abstention check.

| Check | Limit | tesseract-eng-fast | mlkit-latin |
| --- | --- | --- | --- |
| Mean CER, screenshots | <= 0.08 | 0.0 PASS | 0.00018670649738610902 PASS |
| Mean CER, receipts | <= 0.15 | 0.1947820478529403 **FAIL** | 0.02328556294786782 PASS |
| Mean CER, letters | <= 0.10 | 0.001246465999739051 PASS | 0.001747922254587778 PASS |
| Lines matched at IoU >= 0.5 | >= 0.90 | 0.9021113243761996 PASS | 0.9980806142034548 PASS |
| Correct abstention, non-Latin fixtures | 1.0 | 0.0 **FAIL** | 0.0 **FAIL** |
| Abstentions on Latin fixtures | 0 | 0 PASS | 0 PASS |
| Timed-out images | 0 | 0 PASS | 0 PASS |
| p95 duration (ms) | <= 6000 | 1734 PASS | 675 PASS |
| Peak PSS delta (KiB) | <= 122880 | 50398 PASS | 53900 PASS |

Gate verdict: **tesseract-eng-fast FAIL** (2 checks failed);
**mlkit-latin FAIL** (1 check failed). Neither engine permits default.

The Tesseract line match rate passes by 0.0021 (0.9021 against 0.90).

### Non-Latin and mixed fixtures (the five `expected_abstain` samples)

For both engines, production returned OUTPUT on all five. None abstained.
The gate's abstention check therefore reads 0.0 for both.

| Fixture | Class | tesseract outcome | mlkit outcome | Non-Latin lines accepted (both policies, tesseract / mlkit) |
| --- | --- | --- | --- | --- |
| screenshot-07 | non_latin | OUTPUT | OUTPUT | 10 / 10 |
| screenshot-14 | mixed | OUTPUT | OUTPUT | 1 / 1 |
| receipt-07 | mixed | OUTPUT | OUTPUT | 1 / 1 |
| receipt-12 | mixed | OUTPUT | OUTPUT | 1 / 1 |
| letter-08 | non_latin | OUTPUT | OUTPUT | 9 / 9 |

Whole-revision and per-span policy gave identical counts on these five
fixtures, with 0 Latin lines lost. The V-15 difference therefore did not
show up on this device run. The R5 decision is still deferred (see the
2026-11-25 section above). Nothing here changes it. The `non_latin_accepted`
counts are the policy fields in the results JSON, computed by
`ScriptPolicyCost` from each engine's raw spans. They are not a per-line
transcription. No engine output text is recorded here.

### Timings and memory

Gate per-image numbers, 40 fixtures per engine. Duration is the production
`extract` call.

| Engine | Min ms | Median ms | p95 ms (gate) | Max ms | Timeouts |
| --- | --- | --- | --- | --- | --- |
| tesseract-eng-fast | 993 | 1343 | 1734 | 1774 | 0 |
| mlkit-latin | 376 | 526 | 675 | 789 | 0 |

R3 names the p95 limit "on the CI emulator". These numbers come from the
tablet and do not test that wording.

`OcrEngineMeasurementTest` (3 runs per fixture, after a warm-up; times in ms):

| Engine | Fixture | Runs (ms) | Peak PSS (KiB) |
| --- | --- | --- | --- |
| tesseract-eng-fast | receipt-900x360 (2 spans) | 677, 695, 711 | 64263 |
| tesseract-eng-fast | screenshot-1080x2400 (20 spans) | 2113, 2135, 2083 | 76512 |
| tesseract-eng-fast | dense-1200x1600 (40 spans) | 3101, 3061, 2967 | 68323 |
| mlkit-latin | receipt-900x360 (2 spans) | 304, 290, 298 | 62950 |
| mlkit-latin | screenshot-1080x2400 (20 spans) | 885, 755, 702 | 91953 |
| mlkit-latin | dense-1200x1600 (40 spans) | 982, 961, 966 | 98889 |

### Tesseract result: the 2026-11-25 reading was incomplete

The P1-21 device note (`docs/verification/P1-21-min-sdk-26.md`, run C5)
said that no Tesseract result appeared in its run. The stored logcat of that
same run contains the Tesseract results (scratchpad
`device-logs/vault-results-optin-eval2/.../logcat-org.openlife.vault.ocr.eval.OcrEvaluationTest-evaluateRegisteredEnginesOverTheFixtureSet.txt`,
lines 2953 to 2962). Those lines carry the same figures as this run:
`cer_mean_receipt` 0.1947820478529403 and `abstention_correct_rate_non_latin`
0.0. The earlier reading was therefore a partial capture, not an engine that
produced no result. Engine output was never missing in this run, so there is
no cause to report for an absent Tesseract result.

### Known gaps

- The `P204Eval` logcat copy did not appear in this run's logcat capture
  (the `-s P204Eval:I` dump was empty). The results JSON files are the
  complete record. The `P203Measure` tag captured fully.
- The gate's `timeouts` check (limit 0) is not yet owner-reviewed, as the
  ADR-0003 notes say.
- No ADR-0003 addendum and no P2-05 change were made in this run.
