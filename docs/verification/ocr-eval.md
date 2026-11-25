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
