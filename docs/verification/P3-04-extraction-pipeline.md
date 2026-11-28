# P3-04 verification: extraction pipeline, evidence links and staleness

Branch `plan/P3-04-extraction-pipeline`. Commits: claim `3319c6a`, RED `df14add`, GREEN `ffc162e` (includes the test-shape fixes), REFACTOR `3300ede`.
Date of run: 2026-11-28 (step started 2026-11-27).

## Commands and real results

| Command | Result |
| --- | --- |
| `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test` (foreground re-run, log `scratchpad/p304-gate2.log`) | `BUILD SUCCESSFUL in 5m 2s` |
| The same command, earlier background run (log `scratchpad/p304-gate.log`) | `BUILD SUCCESSFUL in 2m 43s`, `GATE_EXIT=0` |
| `./gradlew detekt lint` after the refactor (log `scratchpad/p304-detekt5.log`) | `BUILD SUCCESSFUL in 4m 49s` |
| `./gradlew :vault:testDebugUnitTest --tests 'org.openlife.vault.repository.FactExtractionCoreTest'` | 8 tests, 0 failures, 0 errors |
| `scripts/plan-check.sh` | `steps=83 errors=0`, `runnable now: []` |
| `bash scripts/tests/run.sh` | `51 passed, 0 failed` |
| `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.FactExtractionRepositoryTest` on tablet `U8BBB20806201256` (device `AGS2-W09`, Android 8.0.0) | `BUILD SUCCESSFUL in 1m 9s`; 8 tests, 0 failures, 0 errors, 0 skipped |

Device setup: `stay_on_while_plugged_in` was set to 3 for the run and restored to 0 (read back as `0`). Only the new class was run. No lock credentials were changed. Nothing else was installed or removed.

## Host tests (FactExtractionCoreTest, 8 cases)

Each case is named after the step's R-clauses and the tdd.red list:
`extractsFactsWithEvidenceLinksFromReadySpans`, `rejectedOcrRevisionYieldsNoFacts`, `rerunIsIdempotentForUnchangedRevision`, `newOcrRevisionMarksPreviousFactsStale`, `limitOverflowCommitsNothing`, `deadlineProducesFailedResultAndNoPartialRows`, `ungroundedAndOutOfScriptSpansYieldNoFacts`, `usesCurrentRevisionPointerAndCorrectionChain`.

Mutation checks (run during the GREEN step): four boundary tests that assert "nothing written" were each shown to fail on their intended test when the forbidden write was added, then the file was restored.

## Device tests (FactExtractionRepositoryTest, 8 cases, SQLCipher)

The same eight names, run on the tablet. All passed (`vault/build/outputs/androidTest-results`, `testsuite tests="8" failures="0" errors="0"`).

## Requirement and constraint status

- Exhaustive kind mapping: `mapKind` in `FactExtractionMatching.kt` has no `else`. Proven by compilation (gate). No dedicated test.
- No `UserValueShape` on candidates: grep finds no reference in the extraction core or matching files. Proven by absence only, no test.
- Unique ordinals for `CurrencyContextIndex`: the index enforces this with `require`. `ExtractorSafetyTest.duplicateSpanOrdinalsAreRejected` covers it.
- Stale hooks inside existing transactions with epoch bump: `OcrRepository` hooks and `FactRepository` call `FactStaleMarker` inside `database.withTransaction`. `FactWriterTest` and the androidTest cover marking. Epoch bump is exercised by `newOcrRevisionMarksPreviousFactsStale`.
- markStale throw fails the run with no half-written facts: not tested. No test forces a throw inside the hook.
- C2-R31 fingerprint re-check, SUPERSEDED with no writes: implemented in `FactExtractionCore.commit`. No test exercises the SUPERSEDED path (grep finds none).
- C2-R40 (per-trigger table): implemented through the hooks above. No dedicated test per trigger row.
- C2-R29 deadline, C2-R57 64-candidate cap, C2-R62 500 cap: covered by `deadlineProducesFailedResultAndNoPartialRows`, `limitOverflowCommitsNothing`. The per-kind 64 skip has no direct test.
- C2-R30 auto-run: `extractIfDue` is wired through `OpenLifeApp`. Not exercised on device.

## Gaps (unverified)

1. SUPERSEDED path (C2-R31) has no test.
2. markStale throw rollback has no test.
3. Per-kind 64 skip (C2-R57) has no test.
4. Auto-run from OCR READY (C2-R30) is not exercised end to end on device.
5. Instrumented runs cover only FactExtractionRepositoryTest, not the full `:vault:connectedDebugAndroidTest` suite.
