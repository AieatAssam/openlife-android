# P3-06 verification: facts evaluation set (2026-11-28)

Branch `plan/P3-06-facts-eval`. Text part: RED `a5043c3`, GREEN `67847c2`,
REFACTOR `4f66788`, DOCUMENT commit. Image part: fixtures and generator
`b3a47c2`, gate copy `c36f0d2`, androidTest wiring and boundary test `1346f9f`,
classification by surface form `3f76a56`, then the image documentation commit.
Metric tables and known deviations: `docs/verification/facts-eval.md`. Raw tablet
reports: `docs/verification/facts-image-results/`.

## Commands and real output

Gradle runs were foreground, one at a time, after checking that no other
`GradleWrapperMain` process was running.

| Command | Result | Log (scratchpad) |
| --- | --- | --- |
| `./gradlew detekt lint :vault:test :eval:test` | `BUILD SUCCESSFUL` (2m 12s) after the boundary fix. The first run failed only on `EvalBoundaryTest.onlyTestConfigurationsReferenceTheEvalModule`. | `p306-verify-c.log` |
| `./gradlew detekt lint assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL in 2m 1s` | `p306-verify-d.log` |
| `./gradlew :app:test` | `BUILD SUCCESSFUL in 5s` | `p306-app-test-full.log` |
| `scripts/plan-check.sh` | `steps=83 errors=0`, `runnable now: []` | `p306-plan-check.log` |
| `bash scripts/tests/run.sh` | `51 passed, 0 failed` | `p306-scripts-tests.log` |
| `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.facts.eval.FactsImageEval -Pandroid.testInstrumentationRunnerArguments.p306FactsImage=true` on the tablet (adb serial `U8BBB20806201256`; `pgrep -f connectedDebugAndroidTest` and `pgrep -f '[G]radleWrapperMain'` empty first; stay-on set, then restored to 0) | Test passed: 1 test, 0 failures, 0 errors, 0 skipped, about 26.5 s, both engines. The measured R2 image gate is FAIL on both engines. Recorded, not tuned. | `docs/verification/facts-image-results/facts-image-tesseract-eng-fast-2026-11-28.txt`, `docs/verification/facts-image-results/facts-image-mlkit-latin-2026-11-28.txt` |

The background run `./gradlew detekt lint :vault:test :eval:test :app:testDebugUnitTest`
(`p306-verify-b`) was stopped by the session while the app unit tests were
running. A later background run reported `BUILD FAILED` in
`:app:testDebugUnitTest` with no test name in its output. A foreground rerun
of `:app:testDebugUnitTest` then passed (`p306-app-test.log`). The cause of the
background failure was not found, so it stays open.

## Gate

`FactsEvalTest.gateThresholdsAreMet` passes on `:vault:test`. The text-part
numbers are in `docs/verification/facts-eval.md`. The gate was not loosened and
no rows were excluded.

The image part (R2 applied to the 30 image cases through OCR) is measured on the
tablet and FAILS on both engines: Tesseract (eng fast) date precision 28/29 and
phone precision 4/5; ML Kit (latin) date precision 26/27, money 12/13 and URL 3/4.
These are recorded as measured. The thresholds were not changed and the fixtures
were not tuned.

## Edits in this step (outside the new facts files)

- `eval/src/test/kotlin/org/openlife/eval/ocr/EvalBoundaryTest.kt`: the
  allowlist of `vault/build.gradle.kts` lines that mention eval gains the
  `factsSet` input line and the facts image asset line, and assertions check
  that the facts set is a vault unit-test input and that the image set is an
  androidTest asset only. This is a test-file change. No extractor or pipeline
  production code changed.
- `vault/build.gradle.kts`: the `factsSet` input (from RED). The androidTest
  sourceSet adds `eval/facts/v1/images` as assets (P3-06-R1).
- New: `eval/build.gradle.kts` task `generateFactsImages`,
  `eval/src/main/kotlin/org/openlife/eval/facts/fixtures/FactsImageGenerator.kt`,
  `eval/src/test/kotlin/org/openlife/eval/facts/FactsImageSetTest.kt` (2 tests
  pass), `vault/src/androidTest/java/org/openlife/vault/facts/eval/FactsImageEval.kt`,
  `eval/facts/v1/image-cases.tsv`, `eval/facts/v1/images/`.

## Gaps and deviations

- **Image part measured; R2 gate fails.** See `docs/verification/facts-eval.md`.
  `FactsImageEval` records the gate and does not assert it. It fails only for an
  invalid set or an unprocessed image.
- **Command form.** The step lists `--tests '*FactsImageEval*'`. The tablet run
  used the class argument with the opt-in flag, one class per invocation.
- **Deviation: persistence path.** The image path does not use the Room /
  `FactExtractionCore` path from P3-04. It runs `FactExtractor.extract` over the
  OCR spans. Not repaired here.
- **Conflict: rendering.** The brief said the images are rendered to a bitmap on
  the device. They are host-rendered PNG assets packaged into the androidTest APK,
  reusing the P2-04 OCR fixture renderer and IBM Plex. Owner to decide.
- **Not reported for the image part:** abstention and evidence-location
  correctness per kind (R1). Gap.
- **Dependency.** `depends_on: [P3-04]`. P3-04 was at `review` when this step started.
- **Known deviations (text part):** D-1 M-20 (two silent MONEY misses) and D-2
  T-14 (expected abstention not recorded). Details in `docs/verification/facts-eval.md`.
- **Unexplained:** the background `:app:testDebugUnitTest` failure above (open).
