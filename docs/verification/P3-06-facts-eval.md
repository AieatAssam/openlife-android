# P3-06 verification: facts evaluation set (2026-11-28)

Branch `plan/P3-06-facts-eval`. Commits: RED `a5043c3`, GREEN `67847c2`,
REFACTOR `4f66788`, then the DOCUMENT commit (boundary test, docs, step file).
Metric table and known deviations: `docs/verification/facts-eval.md`.

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

The background run `./gradlew detekt lint :vault:test :eval:test :app:testDebugUnitTest`
(`p306-verify-b`) was stopped by the session while the app unit tests were
running. A later background run reported `BUILD FAILED` in
`:app:testDebugUnitTest` with no test name in its output. A foreground rerun
of `:app:testDebugUnitTest` then passed (`p306-app-test.log`). The cause of the
background failure was not found, so it stays open.

## Gate

`FactsEvalTest.gateThresholdsAreMet` passes on `:vault:test`. The numbers are
in `docs/verification/facts-eval.md`. The gate was not loosened and no rows
were excluded.

## Edits in this step (outside the new facts files)

- `eval/src/test/kotlin/org/openlife/eval/ocr/EvalBoundaryTest.kt`: the
  allowlist of `vault/build.gradle.kts` lines that mention eval gains the
  `factsSet` input line, and one assertion checks that the facts set is a
  vault unit-test input. This is a test-file change. No extractor or pipeline
  production code changed.
- `vault/build.gradle.kts`: the `factsSet` input (from RED). Test task input
  only.

## Gaps and deviations

- **Instrumented image part not run.** The step lists
  `./gradlew :vault:connectedDebugAndroidTest --tests '*FactsImageEval*'`.
  No class `FactsImageEval` exists. The 30 image cases are deferred under
  decision Q-12 until the OCR gate is measured (`docs/verification/ocr-eval.md`,
  NO_MEASUREMENT). This is recorded as a gap, not as a pass.
- **Device gap.** No instrumented run on the tablet (`U8BBB20806201256`). Only
  compile checks of the androidTest sources were run. No emulator was used.
- **Dependency.** `depends_on: [P3-04]`. P3-04 is at `review`, not `done`.
  This step was started while P3-04 was at `review`. The brief named P3-04 as
  the dependency and did not say it must be `done`, so this is recorded as a
  dependency note.
- **Known deviations (fixture expectation vs extractor output):** D-1 M-20
  (two silent MONEY misses) and D-2 T-14 (expected abstention not recorded).
  Details in `docs/verification/facts-eval.md`.
- **Unexplained:** the background `:app:testDebugUnitTest` failure above.
