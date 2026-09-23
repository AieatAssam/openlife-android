# P2-02 verification: OCR engine lifecycle, cancellation and memory hygiene

Date: 2026-09-23. Branch: `plan/P2-02-ocr-lifecycle`. Device: `dev36` (API 36).
API 29 and API 36 evidence comes from CI on the PR.

## RED (e888428)

Every test failed on the unchanged tree for the intended reason:

| Test | Failure |
| --- | --- |
| `OcrRepositoryTest.commitFailureBetweenRevisionAndSpansLeavesNoReadyRevision` | a READY revision with no spans |
| `OcrRepositoryTest.cancellationDuringCaptureMarksRevisionCancelledNotRunning` | the revision stayed RUNNING |
| `OcrRepositoryTest.engineTimeoutIsReportedAsCancelledTimeoutAndDoesNotPropagate` | `TimeoutCancellationException` escaped `runOcr` |
| `OcrRepositoryTest.repositoryDeadlineIsReportedAsTimeout` | expected TIMEOUT, was CANCELLED |
| `OcrRepositoryTest.decodeFailureIsEngineFailureNotLimitExceeded` | `IllegalArgumentException` reported as LIMIT_EXCEEDED |
| `OcrRepositoryTest.inputBytesAreZeroedAfterExtraction` | the plaintext was not zeroed |
| `OcrRepositoryTest.cancellingTheJobCancelsTheEngineTaskBeforeBitmapRecycle` | events were `[bitmap recycled]` only: the task was never cancelled or awaited |
| `ViewerScreenC1Test.deletingTheSourceWhileOcrTextIsShownClearsItAndRemovesRows` | the OCR state was never forgotten after deletion |

## GREEN (af3084d) and REFACTOR (258b139)

- **Commit.** The final commit is `database.withTransaction { READY + spans }`
  under `NonCancellable`. A failure ends the revision FAILED/STORAGE_FAILURE
  (a new, additive reason).
- **Capture.** Cancellation after the RUNNING row may exist marks it
  CANCELLED inside capture's own mutation. `markTerminal` would wait on
  that same mutation and deadlock.
- **Deadline.** The single deadline is injectable, and ML Kit's inner
  `withTimeout` is gone. A timeout from any layer while the run is still
  active becomes CANCELLED/TIMEOUT. The UI shows it as a failure ("it took
  too long, so OpenLife stopped it").
- **Exceptions.** `OcrDecodeException` and `OcrEngineException` are typed,
  and `OcrLimitExceededException` still maps to LIMIT_EXCEEDED.
- **Engine task.** `awaitEngineTask` cancels the task, then waits for it
  to settle, under `NonCancellable` for up to 5 s. The ML Kit adapter
  cancels by closing the recognizer, and recycles its bitmap only once the
  task has settled. A task that never settles leaks its bitmap to the GC
  rather than being recycled under a running recognizer.
- **Plaintext.** `OcrEngineInput.close()` zeroes the bytes in the run's
  `finally`.
- **Deletion.** `OcrViewModel` observes the visible sources, and cancels
  and forgets OCR for deleted ones.
- **State machine.** `OcrRunStateMachine` holds the pure decision rules,
  covered by 7 JVM tests.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :vault:test` | pass (includes `OcrRunStateMachineTest` 7/7) |
| `OcrRepositoryTest` (connected) | 13/13 |
| `ViewerScreenC1Test` (connected) | 4/4 |
| `./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest` | vault 99/99, app 72/72 |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL |

## Limits

- **ML Kit cancellation is best effort.** Closing the recognizer abandons
  the task, but the adapter has no cancellation token to pass. The bounded
  wait before recycling is the safety net until P2-05 removes ML Kit.
- **Copies are not guaranteed zero.** The captured plaintext array is
  zeroed. Copies made by the platform decoder or by ML Kit cannot be
  guaranteed to be erased; `docs/THREAT_MODEL.md` already states that JVM
  and native copies are out of reach.
