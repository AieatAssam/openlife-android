# P2-04 verification: OCR evaluation set and gate

- **Date:** 2026-11-25.
- **Branch:** `plan/P2-04-eval-fixtures`.
- **Host:** shared 4-core VM, JDK 21.0.13.1 (nixos).
- **Started with P2-03 still in review**, by coordinator decision on
  2026-11-25.
- **No device was used.** Per the owner's instruction, no emulator, no adb
  and no CI dispatch.
- **Results summary:** `docs/verification/ocr-eval.md`.

## What was built

| Piece | Where | Proven on the host by |
| --- | --- | --- |
| 40 seeded synthetic fixtures with line and column-segment truth, plus a digest manifest | `eval/ocr/v1/`, generator in `eval/src/main/kotlin/.../fixtures` | `FixtureGeneratorTest` (5) |
| Gate thresholds and engine pins (R3, R4) | `eval/ocr/v1/gate.json` | `GateFileTest` (2); vault `GatePinsTest` (1) |
| Metrics: CER, deskewed reading order, segment-aware line matching, p95, PSS delta | `eval/src/core/kotlin/.../OcrMetrics.kt` | `OcrEvaluationTest` (hand-computed oracles); `RealSetGateTest` (perfect recognisers on the committed set) |
| Gate evaluator, a pure function that recomputes every score | `OcrGate.kt` | `OcrEvaluationTest` and `RealSetGateTest`: pass, fail, no measurement, invalid |
| Cost of each script policy (R5) | `ScriptPolicyCost.kt` | `OcrEvaluationTest.strayNonLatinGlyphCostIsMeasuredForBothPolicies`; `vault` `StrayGlyphPolicyOnTruthTest` with the production classifier |
| Strict JSON for truth and results | `Json.kt`, `OcrResults.kt` | `JsonTest` (3) |
| Instrumented harness (R2), `@Slow`, opt-in | `vault/src/androidTest/.../ocr/eval/OcrEvaluationTest.kt` | compiles only (`:vault:compileDebugAndroidTestKotlin`) |
| Gate CLI | `:eval:checkOcrGate` (`OcrEvalReport.kt`) | `OcrEvalReportTest` (exit codes 0, 1, 2, 3, 64; duplicate results) |
| Release-APK boundary | `scripts/check-no-eval-fixtures.sh`, called by `inspect-release-apk.sh` | `scripts/tests/run.sh` (4 cases) plus the mutation below |
| Module boundary | `EvalBoundaryTest` | `:eval` is referenced only by vault's test and androidTest wiring, never by `:app` |

The code under `eval/src/core` uses only the stdlib, `java.security` and
`java.text`. Vault's androidTest compiles the same sources, so the device and
the host compute identical metrics.

## RED (b2af25f)

The RED commit message records each failure on that tree:

- `FixtureGeneratorTest`: 3 tests failed with NotImplementedError stubs;
  `committedSetMatchesTheGenerator` failed because there was no manifest.
- `OcrEvaluationTest`: all 5 tests failed with NotImplementedError.
- `GateFileTest`: both tests failed because there was no `gate.json`.
- `StrayGlyphPolicyOnTruthTest` (vault) failed because there was no manifest.
- `JsonTest` (3) and `EvalBoundaryTest` (1) passed at once. They cover codec
  plumbing and the module boundary.

## GREEN (9bcffab) and verification, 2026-11-25

```text
./gradlew detekt lint :app:test :vault:test assembleDebug \
  :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test
=> BUILD SUCCESSFUL in 5m 25s
   app 88/88, vault 129/129 (incl. StrayGlyphPolicyOnTruthTest 1/1), eval 15/15, 0 failures
./gradlew :eval:generateOcrFixtures  => BUILD SUCCESSFUL; git status eval/ocr: 0 changes (idempotent)
scripts/plan-check.sh                => steps=82 errors=0
bash scripts/tests/run.sh            => 51 passed, 0 failed
```

**Reproducibility.** Two in-memory generations give byte-identical PNGs,
truth and manifest. Another seed changes most images.
`committedSetMatchesTheGenerator` then checks the committed files:

- It regenerates the set and compares the decoded pixels (`raster_sha256`)
  and the truth JSON with the committed files.
- It checks each committed PNG's SHA-256 against the manifest.

**Gate with no measurements.** `./gradlew :eval:checkOcrGate` gave
BUILD FAILED, exit 1:

```text
GATE tesseract-eng-fast: NO_MEASUREMENT (no results file); permits default: false
GATE mlkit-latin: NO_MEASUREMENT (no results file); permits default: false
```

All seven checks for each engine were NOT_MEASURED.

**Packaging.**
- `vault-debug-androidTest.apk` carries the 82 set files under `assets/v1/`.
- `app-debug.apk` reports `EVAL_FIXTURES_RESULT=absent`.
- **Mutation:** I added `getByName("main") { assets.srcDir(eval/ocr) }` to
  `vault/build.gradle.kts` and rebuilt `:app:assembleDebug`. The check failed
  with exit 1, listing `assets/v1/README.md` and
  `assets/v1/fixtures/letter-01.png`. After the revert it reported `absent`
  (exit 0).

## Fonts

- The app's IBM Plex files are Latin-only subsets, so Cyrillic and Greek use
  Liberation Sans 2.1.5 (OFL-1.1, `eval/fonts/`). This is a file in the
  repository, not a Gradle dependency.
- The file was copied from the nixpkgs build, SHA-256
  `cfb8c07f8840806e6f4bb2b71cd8f73be4e94c136e428b943b55f57d41e75fea`. It may
  not be byte-identical to the upstream release archive.
- There is no CJK fixture, since no vendored font covers CJK. R5 allows CJK
  or Cyrillic.

## Review round, 2026-11-25

An independent review returned "accept after fixes". The fixes:

- **X-01, receipt columns.**
  - 144 of the 521 Latin truth lines are receipt item and price rows.
    They were drawn as one box, so an engine that reads item and price
    as two spans could match none of them. The line match rate would
    then be at most 0.7236.
  - Truth is now schema `openlife-ocr-truth/2`. Each line carries
    `segments`, one per column, split at a run of 3 or more spaces, each
    with its own ink box. Receipt rows now always have a gap of at least
    3 spaces.
  - A line matches through its whole box or through all of its segments.
    The rule is in `eval/ocr/v1/README.md`. CER still uses whole lines.
  - The generator version is now 2. The set was regenerated, and the
    reproducibility test still passes on this runtime.
- **X-02, reading order on skewed receipts.**
  - Span centres are rotated back by the fixture's recorded skew before
    rows are grouped.
  - This uses only image geometry, never truth text or truth boxes. Its
    limits are listed in the README.
  - `RealSetGateTest` checks that a perfect per-column recogniser and a
    perfect whole-row recogniser both reach CER 0 and full line match on
    every Latin fixture. That includes all 13 Latin receipts, each of
    them skewed, 10 by 1 degree or more.
- **X-03, acting-owner ruling.**
  - `abstentions_on_latin` now fails an engine on one abstention on a
    Latin fixture.
  - This is stricter than R3's wording, under which an abstention only
    raised a category's CER mean.
  - `RealSetGateTest.oneAbstentionOnALatinFixtureFailsTheGate` shows the
    difference on the real manifest. With one Latin screenshot or receipt
    abstaining, every mean and the line match rate still pass, and only
    the new check fails.
- **X-04, recorded scores.** The gate recomputes each fixture's CER and
  matched-line count from the recorded spans. CER may differ by at most
  1e-9, the count must be equal, and any disagreement makes the results
  INVALID.
- **X-05, provenance.**
  - `gate.json` pins `tesseract-eng-fast` to
    `t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd` and `mlkit-latin` to
    `16.0.1`. These are the values production already records. Vault's
    `GatePinsTest` ties the pins to `TesseractOcrEngine.MODEL_VERSION`
    and `MlKitOcrEngine.modelVersion`.
  - Results record `code_commit` (the harness requires the `p204Commit`
    argument) and the device.
  - A different model version is INVALID ("stale").
- **X-06, reproducibility across JDKs.**
  - The manifest records `rendered_with`: `21.0.13.1+2-nixos`, Oracle
    Corporation VM, Linux amd64.
  - Only the pixel regeneration test is skipped, through `Assume`, on
    another runtime. The digest and decode checks always run.
  - CI's build job now runs `:eval:test`, and
    `scripts/tests/p0-04-ci-workflow.sh` requires it. The check was shown
    to fail with the CI line reverted.
- **X-07, logcat.**
  - The logcat copy is now split into entries of at most 3000 UTF-8
    bytes. `Utf8Chunks` is shown on Cyrillic text in `JsonTest`.
  - Where results land and how to pull them without root is in the
    README.
  - The harness states that only synthetic text may be logged.
- **X-08, CLI and duration.**
  - Two results files for one engine are INVALID ("duplicate results").
  - The README now states that measured durations include Tesseract's
    per-call model load. Production code is unchanged.
- **Also.**
  - `OcrEvalReportTest` covers the CLI exit codes.
  - The harness gives each `extract` 60 s. A call that has not returned
    is recorded as `timed_out`.
  - The new `timeouts` check fails on any timeout. This was added in this
    round for the same reason as X-03, and is **not yet owner-reviewed**.

**Open suspicion from the review.** Both engines use English-only Latin
models, so they may be unable to emit Cyrillic or Greek letters at all.
If so, they would read the five non-Latin fixtures as Latin look-alikes,
production would never abstain, and abstention correctness would be 0/5
by construction rather than through an engine fault. The first device run
must check this (item 1 below).

### First-device-run checklist

1. Whether either engine outputs non-Latin letters on `screenshot-07`,
   `letter-08`, `receipt-07` and `receipt-12`. Look at `raw_spans` in the
   results.
2. How each engine segments receipt columns: one span per row, or item
   and price apart. Look at the `spans` of a receipt.
3. Whether the results files arrive in
   `vault/build/outputs/connected_android_test_additional_output/`
   (`additionalTestOutputDir`).
4. Whether model load and the 50 ms `getPss` sampler inflate durations.
   Compare the first and later images, and Tesseract with ML Kit.
5. Memory growth across images: whether `baseline_pss_kib` rises through
   the run.
6. Whether the harness's engine ids and model versions match `gate.json`.
   The harness asserts this before measuring.

### Verification after the review fixes

```text
./gradlew :app:cleanTestDebugUnitTest :vault:cleanTestDebugUnitTest :eval:cleanTest detekt lint \
  :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin :eval:test
=> BUILD SUCCESSFUL in 7m 10s
   app 139/139, vault 154/154 (incl. GatePinsTest 1/1, StrayGlyphPolicyOnTruthTest 1/1),
   eval 26/26, 0 failures, 0 skipped (the regeneration test ran: same runtime)
scripts/plan-check.sh                => steps=82 errors=0
bash scripts/tests/run.sh            => 51 passed, 0 failed
./gradlew :eval:checkOcrGate         => exit 1; both engines NO_MEASUREMENT (no results file)
```

CI contract mutation: with `:eval:test` removed from `ci.yml`,
`scripts/tests/p0-04-ci-workflow.sh` reported
`not ok - CI runs the OCR evaluation tests (P2-04)` and exited 1. With the
line restored it passed.

These parts have not run anywhere: the harness's timeout path, its
logcat chunking on a device, and its results retrieval. They are compiled
only.

## Gaps: pending a physical-device run

| Item | Command |
| --- | --- |
| Engine results for both engines (R2), the gate outcome (R3), and the per-engine R5 policy cost | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.eval.OcrEvaluationTest -Pandroid.testInstrumentationRunnerArguments.p204Eval=true -Pandroid.testInstrumentationRunnerArguments.p204Commit=$(git rev-parse HEAD)` on tablet `U8BBB20806201256` |
| Timeout path, logcat chunking and results retrieval in the harness | the same run; these are compiled but have not run on a device |
| Gate verdict on those results | `./gradlew :eval:checkOcrGate -PocrResults=<dir>/results-tesseract-eng-fast-<date>.json,<dir>/results-mlkit-latin-<date>.json` |

Every engine measurement is a gap pending a physical-device run. No figure
was estimated.

## Deviations from the step file

- **Where results go.** The step asks for
  `eval/ocr/v1/results-<engine>-<date>.json`. The device cannot write into
  the repository, so the harness writes to the test output directory and to
  logcat. Commit the files into `eval/ocr/v1/` after the run.
- **Where the named tests live.** The named `OcrEvaluationTest` tests for
  metrics, the gate and R5 are in the eval JVM test class. The instrumented
  class of the same name only runs the engines.
- **Opt-in argument.** The device command needs the `p204Eval=true` argument.
  Without it, the class is reported as ignored.
- **Device.** The step names the CI emulator for the duration figure. The
  owner forbids emulators, so the physical tablet replaces it.
- **R5 decision deferred.** The production policy is unchanged and no ADR
  addendum has been opened.
- **No engine verdict.** The acceptance criterion requires both engines to
  have recorded results and a Tesseract pass or a RapidOCR fallback decision.
  It is not met.
- **CI.** `:eval:test` runs in CI's build job since the review round.
- **Interpretations and scope.**
  - "120 MB" is read as 122880 KiB.
  - The confidence calibration in ADR-0003 R6 is out of scope.
