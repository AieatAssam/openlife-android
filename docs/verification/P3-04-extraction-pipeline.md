# P3-04 verification: extraction pipeline, evidence links and staleness

Branch `plan/P3-04-extraction-pipeline`. Commits: claim `3319c6a`, RED `df14add`, GREEN `ffc162e` (includes the test-shape fixes), REFACTOR `3300ede`.
Review fixes P-01..P-07: host RED `ac5cfa7`, host GREEN `aaf72d1`, device RED `7d0aa1b`, device GREEN `365f452`,
T1 rollback test `479e839`, pointer boundary test `9059789`, mapKind test `0b1b4a4`, style fixes `b75ede4`.
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

## Review fixes P-01..P-07

Gate after the fixes (foreground, `scratchpad/gate.log`, tree at `b75ede4`):
`./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`
-> `BUILD SUCCESSFUL in 4m 23s`. `scripts/plan-check.sh` -> `steps=83 errors=0`, `runnable now: []`.
`bash scripts/tests/run.sh` -> `51 passed, 0 failed`.

| Finding | Change | Test (host unless marked device) | Result |
| --- | --- | --- | --- |
| P-01 late finisher | A replaced reading marks the previous reading's facts only when the pointer move succeeded (`moveCurrentReading == 1`) | `lateFinisherDoesNotStaleCurrentFacts` (device) | pass |
| P-02 duplicate keys | Duplicate detection ignores STALE current rows; continuation matches stale rows by kind and canonical text | `rerunAfterClarifyCreatesNothing`, `continuationRequiresEqualCanonicalText` (host) | pass |
| P-03 cancellation | `commit` and the run-row write run under `NonCancellable`; a non-cancellation storage error records a run row and returns STORAGE_FAILURE | `commitIsNotInterruptedByCancellation`, `failedFallbackRunRowStillReturnsStorageFailure`, `failedTerminalRunRowStillReturnsItsOutcome` (host) | pass |
| P-04 rejected readings | Rejecting a reading marks its facts REJECTED; un-rejecting bumps the input epoch; `extractIfDue` refuses a rejected reading | `undoRejectBumpsEpoch` (device), `extractIfDueRefusesARejectedReading` (host) | pass |
| P-05 onReady | `onReady` runs after a completed OCR; its failure is swallowed (cancellation rethrown) and does not change the OCR result | `onReadyFailureStillCompletesOcr` (device), `autoRunAfterReadyCreatesFactsOnce` (device) | pass |
| P-06 per-kind cap | 64 candidates committed in full; 65 skips the kind and reports it; the other kinds commit | `kindAtExactly64IsCommittedInFull`, `kindOver64IsSkippedOthersCommit` (host) | pass |
| P-07 offsets | Evidence offsets are checked against the span text (`EvidenceOffsets.fit`) before a fact write; out of range fails with `OFFSETS_OUT_OF_RANGE` | no dedicated test | defensive guard, see below |

The six C2-R40 stale triggers are device tests named `staleTrigger_<reason>`. The six rows pass on the tablet with no production change, so each arrangement is real.

### T1 rollback

Covered on device by `OcrFactHooksTest.staleMarkFailureRollsBackPointerMoveSpansAndEpoch` (`479e839`). A test-installed trigger aborts the first `fact_revisions` write to STALE during a reading replacement. The test then asserts that the pointer stays on the first reading, that no span rows of the failed revision exist, that no STALE row exists, and that the epoch is unchanged. No production code was changed to reach the path. The test asserts `OcrRunResult.Failed`, so it fails if the induced abort does not happen.

### Recorded mutations (boundary tests that already passed)

Each mutation was applied to `FactExtractionCore.kt`, the test class was run with `:vault:testDebugUnitTest --tests '*FactExtractionGuardTest*'`, and the file was restored with `git checkout`.

| Mutation (production line) | Failing test |
| --- | --- |
| M1 `MAX_CANDIDATES_PER_KIND = 64` changed to `65` | `kindOver64IsSkippedOthersCommit` |
| M2 `MAX_CANDIDATES_PER_KIND = 64` changed to `63` | `kindAtExactly64IsCommittedInFull` |
| M3 continuation candidates `stale.filter` replaced by `stale.asReversed().filter` (tie-break) | `continuationTieBreakIsOverlapThenFactOrdinal` |
| M4 `epoch = dao.sourceEpoch(sourceId)` replaced by `epoch = null` (epoch supersession) | `epochBumpBetweenReadAndCommitWritesNothing` |
| M5 `pointers = dao.factPointers(sourceId).toSet()` replaced by `pointers = emptySet()` (pointer supersession) | `factPointerChangeBetweenReadAndCommitWritesNothing` (added in `9059789`) |
| M6 `sourceState = dao.sourceState(sourceId)` replaced by `sourceState = null` (DELETING supersession) | `deletingSourceBetweenReadAndCommitWritesNothing` |

M5 was first run before its test existed and every guard test still passed, so the pointer comparison was untested. The pointer test closed that gap.

### Findings recorded, not changed

- BASIS traversal depth: a BASIS edge is created only from a USER revision to a non-USER one, so `FactStaleMarker` never reaches depth 2 through the schema. The `FactLimits.MAX_TRAVERSAL_DEPTH` guard is unreachable in practice. It is kept, not removed.
- P-07 has no failing test: no extractor input was found that yields out-of-range offsets, so the check is a defensive guard.

### Device run (full `:vault:connectedDebugAndroidTest --continue`)

Tablet `U8BBB20806201256`, log `scratchpad/device-full.log`. Result from the XML reports (`vault/build/outputs/androidTest-results/connected/debug`): 191 tests, 0 errors, 0 skipped, 3 failures. The three failures are the opt-in measurement tests, which the coordinator expected to fail:
`OcrEngineMeasurementTest.measureEnginesOnSyntheticFixtures`, `OcrEvaluationTest.evaluateRegisteredEnginesOverTheFixtureSet`, `RecoveryTimingTest.recoveryTimeWithFiftySources`.
Their failure elements carry no message, and each takes under 10 ms, so they fail without their opt-in argument rather than measuring anything. Gradle printed `BUILD SUCCESSFUL in 5m 15s` despite these three failures; the XML reports are the authority here.

`stay_on_while_plugged_in` was set to 3 for the run and read back as `0` after the restore.

### Status of the gaps above

1. SUPERSEDED path (C2-R31): tested by `epochBumpBetweenReadAndCommitWritesNothing`, `factPointerChangeBetweenReadAndCommitWritesNothing`, `deletingSourceBetweenReadAndCommitWritesNothing`.
2. markStale throw rollback: tested on device by `staleMarkFailureRollsBackPointerMoveSpansAndEpoch`.
3. Per-kind 64 skip (C2-R57): tested by `kindOver64IsSkippedOthersCommit` and `kindAtExactly64IsCommittedInFull`.
4. Auto-run from OCR READY (C2-R30): tested on device by `autoRunAfterReadyCreatesFactsOnce`.
5. Full suite: run on the tablet as above (191 tests, the three opt-in failures expected).

## Verification fixes (2026-11-28)

Commits on `plan/P3-04-extraction-pipeline`, in order (RED before GREEN for each item):

- `39e99d0 P3-04: detekt fixes in the guard tests (line length, import order)`
- `22fa3b9 P-06: cancellation writes nothing before the commit; NonCancellable only inside the transaction`
- `15df3b4 P-06: RED test for a run cancelled before its commit`
- `1740521 P-03: break continuation ties by fact ordinal (C2-R33)`
- `64ed29d P-03: RED test for the continuation tie-break by fact ordinal`
- `6510058 P-02: key the duplicate check on the full extraction key`
- `5378b50 P-02: RED tests for the stale-clarification and textStart duplicate key`

### P-02: duplicate key (C2-R32 step 1)

The key is `(kind, valueJson, ocrSpanId, ocrUserRevisionId, textStart)` over EXTRACTED history rows of facts whose current revision is not STALE. Two RED tests failed on the previous key, as intended:

- `rerunAfterStaleClarificationExtractsTheValueAgain`: a USER clarification goes STALE through the production marker (BASIS). The re-run must extract the value again. RED: the value was skipped.
- `sameValueAtAnotherOffsetIsWrittenAgain`: the same date at a second `textStart` in the same span is written. RED: it was skipped.

Decision: the stale USER fact is not an EXTRACTED continuation target, so the re-extracted value becomes a new fact, not a revision of the old one. The first test asserts that one DATE fact has an EXTRACTED, non-STALE current revision, not the original fact id.

Mutations (run on the GREEN code, file restored after each):

- M7, `textStart` removed from the key: `sameValueAtAnotherOffsetIsWrittenAgain` fails, and `rerunAfterClarifyCreatesNothing` fails too.
- M8, stale-fact exclusion removed: `rerunAfterStaleClarificationExtractsTheValueAgain` fails.

### P-03: continuation tie-break (C2-R33)

`continuationTieFollowsFactOrdinalWhateverTheRowOrder` puts two stale DATE facts on an equal overlap and returns the history rows in reverse. The lower ordinal must continue. RED: the higher ordinal continued. GREEN: `sortedBy { it.first.factOrdinal }` before `maxByOrNull`.

The test uses a `HostFactDao` subclass that reverses `revisionHistoryOfSource`. This is test-side only. The production SQL has no ORDER BY, by decision, and a fact ordinal is assigned as MAX+1 on insert, so no legal insert sequence produces the reversed order. The subclass is the only way to reach this branch. `HostFactDao` is now `open` for it.

Mutation M9, sort removed: `continuationTieFollowsFactOrdinalWhateverTheRowOrder` fails.

The earlier test `continuationTieBreakIsOverlapThenFactOrdinal` passes by insertion order, which matches ordinal order, so it does not exercise the tie-break on its own.

### P-06: cancellation

- `cancelledBeforeCommitWritesNothing` cancels the job inside the gate's `read`. RED: two facts were written. GREEN: `currentCoroutineContext().ensureActive()` before the commit, so the run writes no fact and no run row.
- `cancellationDuringTransactionDoesNotTearTheCommit` replaces `commitIsNotInterruptedByCancellation`. It cancels inside the first `insertFact`, during the transaction, and the commit must still finish with `COMPLETED`, 2 facts and 1 run row. It passed before the change, as a boundary test. `NonCancellable` now wraps only the gate's commit body. The terminal run row keeps `NonCancellable`, after the same check.

Mutations (GREEN code, file restored after each):

- M10, commit body made cancellable (`NonCancellable` removed): `cancellationDuringTransactionDoesNotTearTheCommit` fails.
- M11, pre-commit check removed: `cancelledBeforeCommitWritesNothing` fails.

Decision for the coordinator: a cancelled run throws `CancellationException` and writes nothing. `ExtractionOutcome` has no `CANCELLED` value, and none was added.

### Gate and device results

- `./gradlew :vault:testDebugUnitTest`: 299 tests, 0 failures, 0 errors.
- Full gate, one foreground run (`./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`). It first failed detekt on line length and import order in the guard test, fixed in `39e99d0`. The last line of the passing run: `BUILD SUCCESSFUL in 3m 55s`.
- `scripts/plan-check.sh`: `steps=83 errors=0`, `runnable now: []`.
- Tablet `U8BBB20806201256`, `stay_on_while_plugged_in` set to 3 for the runs and read back as `0` afterwards:
  - `FactExtractionRepositoryTest`: 8 tests, 0 failures, 0 errors, 0 skipped (XML from the run).
  - `OcrFactHooksTest`: 11 tests, 0 failures, 0 errors, 0 skipped (XML timestamp 01:19:38, from the run).

### Open after these fixes

1. C2-R33 specifies a global greedy pairing: take the greatest overlap across all candidates and stale facts, remove the pair, repeat. The code pairs candidate by candidate. Not changed here; for the coordinator.
2. P-07 has no dedicated failing test.
3. The three opt-in measurement tests (`OcrEngineMeasurementTest`, `OcrEvaluationTest`, `RecoveryTimingTest`) fail without their argument, and Gradle still reports `BUILD SUCCESSFUL` for the full connected run. Not re-run in this pass; the XML is the authority.
