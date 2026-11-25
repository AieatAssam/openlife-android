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
| 40 seeded synthetic fixtures with line truth, plus a digest manifest | `eval/ocr/v1/`, generator in `eval/src/main/kotlin/.../fixtures` | `FixtureGeneratorTest` (4) |
| Gate thresholds (R3, R4) | `eval/ocr/v1/gate.json` | `GateFileTest` (2) |
| Metrics: CER, IoU line matching, p95, PSS delta | `eval/src/core/kotlin/.../OcrMetrics.kt` | `OcrEvaluationTest.metricsAreComputedAgainstTruth` (hand-computed oracles) |
| Gate evaluator, a pure function | `OcrGate.kt` | `OcrEvaluationTest` gate tests: pass, fail, no measurement, malformed or unbound |
| Cost of each script policy (R5) | `ScriptPolicyCost.kt` | `OcrEvaluationTest.strayNonLatinGlyphCostIsMeasuredForBothPolicies`; `vault` `StrayGlyphPolicyOnTruthTest` with the production classifier |
| Strict JSON for truth and results | `Json.kt`, `OcrResults.kt` | `JsonTest` (3) |
| Instrumented harness (R2), `@Slow`, opt-in | `vault/src/androidTest/.../ocr/eval/OcrEvaluationTest.kt` | compiles only (`:vault:compileDebugAndroidTestKotlin`) |
| Gate CLI | `:eval:checkOcrGate` (`OcrEvalReport.kt`) | run on the host; reported NO_MEASUREMENT (below) |
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

## Gaps: pending a physical-device run

| Item | Command |
| --- | --- |
| Engine results for both engines (R2), the gate outcome (R3), and the per-engine R5 policy cost | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.eval.OcrEvaluationTest -Pandroid.testInstrumentationRunnerArguments.p204Eval=true` on tablet `U8BBB20806201256` |
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
- **CI.** `:eval:test` is not yet part of the CI command.
- **Interpretations and scope.**
  - "120 MB" is read as 122880 KiB.
  - The confidence calibration in ADR-0003 R6 is out of scope.
