# P1-15 verification: read leases, import slot, atomic deletion, verified duplicates

Date: 2026-09-23. Branch: `plan/P1-15-read-leases`. Device: `dev36` (API 36).
API 29 and API 36 evidence comes from CI on the PR.

## RED (82a61bf)

| Test | Failure on the unchanged tree |
| --- | --- |
| `MutationQueueTest.concurrentReadLeasesDoNotBlockEachOther` | the second reader waits |
| `MutationQueueTest.readLeaseDoesNotMakeTryMutationBusy` | `tryMutation` returned null (busy) |
| `ImportRepositoryTest.prepareIsNotBusyWhileAViewerReadIsInFlight` | Busy (F-29) |
| `ImportRepositoryTest.aStagedImportAwaitingSaveMakesASecondPrepareBusy` | a second prepare succeeded during an idle preview |
| `ImportRepositoryTest.duplicateWithTamperedExistingBlobSavesNewAndMarksExistingCorrupt` | DuplicateFound for an unverifiable original (F-32) |
| `DeletionRepositoryTest.rowRemovalIsAtomicAcrossOcrAndSourceTables` | OCR rows 0 after the failed Source removal (F-31) |
| `IntakeAndListFlowTest.leavingAPreviewWithoutDecidingDoesNotLockOutTheNextShare` | Busy after an abandoned preview (added with the app-side slot release) |

These guard tests passed on the stubs. Each was proven by a mutation,
applied and then reverted:

| Test | Mutation | Result |
| --- | --- | --- |
| `MutationQueueTest.mutationWaitsForActiveReadLeasesAndBlocksNewOnes` | readers register without passing the gate | FAILED (late reader overtakes the waiting writer) |
| `MutationQueueTest.tryMutationIsBusyOnlyWhileAnotherMutationHoldsTheQueue` | `tryMutation` always returns null | FAILED |
| `ImportRepositoryTest.releasingTheImportSlotAllowsANewImport` | `ImportSlot.release` is a no-op | "released slot must allow a new import: Busy" |
| `EnvelopeTamperingThroughRepositoryTest.deletionWaitsForAnActiveViewerRead` | `withMutation` does not wait for readers | "deletion must not overlap an in-flight plaintext read" |
| `RecoveryRepositoryTest.deletionInterruptedAfterMarkingIsResumed` | recovery skips `DELETING` rows | expected 1 resumed deletion, was 0 |

## GREEN (590739b)

- `MutationQueue`:
  - `withReadLease` is shared.
  - `withMutation` and `tryMutation` are exclusive. They hold a fair `Mutex`
    gate and wait until no read lease is active; a waiting writer blocks new
    readers.
  - `acquire` and `tryAcquire` are removed (the REFACTOR item).
- `ImportSlot`: Free, then Preparing, then Staged(id). It is released on
  prepare failure or cancellation, cancel, save completion, and
  `IntakeViewModel.onCleared` (through `OpenLifeApp.releaseImportSlot`).
  Reset reports BUSY while the slot is occupied.
- Import preparation takes a read lease, because it creates only a fresh
  UUID's row and file. Save, cancel, delete, recovery and OCR bookkeeping
  are exclusive.
- `DeletionRepository` deletes the OCR rows and the Source row inside
  `database.withTransaction`.
- `DuplicateVerifier` authenticates the existing original and compares its
  bytes with the stage.

## Verification commands

| Command | Result |
| --- | --- |
| `./gradlew :vault:test` | pass (MutationQueueTest 8/8) |
| `./gradlew :vault:connectedDebugAndroidTest` | 91/92 on the full run. The failure was `RecoveryRepositoryTest.recoveryIsIdempotentAcrossAMixOfStates`: its helper leaves a stage to simulate a crash, then prepares again, which the slot now refuses. The helper now frees the slot, as a new process would; `RecoveryRepositoryTest` 13/13 on rerun. |
| `./gradlew :app:connectedDebugAndroidTest` | 71/71 |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL (rerun with the emulator stopped, after the first run timed out under host load) |

## Deviations and gaps

- **R4 transient:** the brief says `SaveResult.Failed(Transient)`. The
  existing `SaveResult.Failed` object is returned and the stage is kept; no
  new result type was added.
- **Reset BUSY while an import is in progress:** not in the brief. It
  prevents reset from waiting out an import and then silently wiping the
  user's preview under the new lease model.
- **Real `kill -9` during deletion:** `docs/runbooks/kill-during-deletion.md`
  was written but not executed. That is a gap.
- **Manual "scroll a 30-item list while sharing":** not performed. The
  mechanism is covered by `prepareIsNotBusyWhileAViewerReadIsInFlight` and
  the JVM lease tests.

## Environment notes

- A test that holds a read lease and then starts a mutation in the same flow
  deadlocks by design. The first version of
  `prepareIsNotBusyWhileAViewerReadIsInFlight` did this and hung for about
  20 minutes before it was diagnosed from `TestRunner` logcat. The
  `MutationQueue` KDoc now states the rule.
- The shared host reached load averages of 70 to 80 from other sessions.
  The dev36 emulator was SIGKILLed (exit 137, most likely the OOM killer)
  during one such spike, and a `detekt lint … assembleDebug` gate run hit a
  40-minute timeout. Stopping the emulator and the Gradle daemons before
  gate runs helped.
