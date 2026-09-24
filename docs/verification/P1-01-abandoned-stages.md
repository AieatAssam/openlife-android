# P1-01 verification: abandoned stage cleanup

Date: 2026-09-24. Branch: `plan/P1-01-abandoned-stages`. Device: `dev36`
(API 36). API 29 and API 36 evidence comes from CI on the PR.

## Adapting to P1-15

The brief predates P1-15, so its terms map onto the current code as
follows:

- "tryAcquire" is `tryMutation`.
- The "active import" is `ImportSlot.activeSourceId`.
- The foreground pass reads the slot *inside* its mutation, and prepare
  now moves the slot to `Staged(id)` while it still holds its read lease.
  A freshly prepared stage is therefore never mistaken for an abandoned
  one. The pass skips while an import is still preparing.

## RED (46e1045)

| Test | Failure |
| --- | --- |
| `RecoveryRepositoryTest.cleanAbandonedStagesRemovesOtherStagedRowsButKeepsTheActiveOne` | expected 1 cleaned, was 0 |
| `RecoveryRepositoryTest.cleanAbandonedStagesSkipsWhileAMutationIsInProgress` | expected null, was 0 |
| `IntakeAndListFlowTest.clearingTheIntakeViewModelWithAnUnsavedPreviewCancelsTheStage` | stage row still present |
| `IntakeAndListFlowTest.clearingDuringABlockedProviderReadLeavesNoStageOnceTheReadReturns` | next import Busy: the slot was orphaned (the note left by P2-02) |
| `IntakeAndListFlowTest.foregroundingMainActivityCleansAbandonedStages` | abandoned stage still present |

Two guards passed on the stubs and are proven by mutation:

| Test | Mutation | Result |
| --- | --- | --- |
| `cleanAbandonedStagesLeavesRowWhenFileCannotBeRemoved` | delete the row even when its file remains | expected 0, was 1 |
| `recreatingTheIntakeActivityKeepsTheStage` | cancel the import in `IntakeActivity.onStop` | "rotation must keep the stage" |

## GREEN (631587f) and REFACTOR (f0bd863)

- **Housekeeping.** `ImportHousekeeping` is owned by `OpenLifeApp` and
  holds `appScope` (SupervisorJob + IO).
- **ViewModel clear.** `IntakeViewModel.onCleared` frees the slot
  synchronously, then cancels the stage on `appScope`.
- **Light recovery.** `RecoveryRepository.cleanAbandonedStages` runs from
  `MainActivity.onStart`.
- **Cancellation during a prepare.** `ImportRepository.prepareImport`
  cancels its own stage on `CancellationException`, and `IntakeViewModel`
  cancels a stage that finished after its screen went away.
- **Bug found.** `updatePreparedSource` and `insertStagedSource` caught
  `CancellationException` as a generic `Exception`. That turned a
  cancellation into `Failed` and left the stage file and row behind. Both
  now rethrow it, and any update failure cancels the stage.
- **Refactor.** A shared `ArtefactFileOps.deleteArtefacts` extension
  replaces three copies. It is an extension so that fault-injection
  wrappers keep their overrides.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew detekt :vault:test :app:test` | pass |
| `./gradlew :vault:connectedDebugAndroidTest` | 102/102 (after the refactor) |
| `./gradlew :app:connectedDebugAndroidTest` | 76/76 |
| Manual: share, preview, Home, return to list, `run-as … ls no_backup/vault/artefacts` | 1 `.stage` during preview; 0 after Home; 0 after returning to the list; no process restart |

The first manual attempt omitted `--grant-read-uri-permission`, and the
intake validator correctly refused it. The rerun used the grant.
