# P1-13 verification: threading and lifecycle correctness

Date: 2026-09-23. Branch: `plan/P1-13-threading-lifecycle`. Device: `dev36`
(API 36, google_apis x86_64). API 29 evidence comes from CI on the PR.

## RED

| Commit | Test | Failure on the unchanged tree |
| --- | --- | --- |
| 424c0b4 | `SourceViewRepositoryTest.transientKeystoreFailureReturnsTransientAndLeavesRowReady` | expected Transient, was Unavailable |
| 424c0b4 | `SourceViewRepositoryTest.tamperedBlobAtReadMarksCorruptOnce` | expected Corrupt, was Unavailable |
| 424c0b4 | `RepositoryDispatcherTest.saveViewAndDeleteCalledFromMainNeverRunKeystoreWorkOnMain` | 3 Keystore calls on the main looper |
| b2afe48 | `ViewerScreenTest` (2) | no unreadable explanation or Delete; no Try again |
| b2afe48 | `MainThreadPolicyTest.saveViewDeleteAndOcrNeverTouchDiskOrCryptoOnMain` | main-thread disk I/O in save, view, OCR capture/extract and delete |
| b2afe48 | `SourceListScreenTest.trimmingWhileRowsAreVisibleDoesNotDrawARecycledBitmap` | "Canvas: trying to use a recycled bitmap" (F-28) |
| b2afe48 | `IntakeScreenFlowTest.cancelDuringPreparingClosesStreamAndLeavesNoStage` | no Cancel while Preparing |
| b2afe48 | `OpenExistingIntentTest`, `ManifestBoundaryTest.mainActivityIsSingleTask…` (JVM) | no NEW_TASK; no singleTask |
| e2d40fb | `FirstRunFlowTest.acknowledgingFirstRunShowsTheListWithoutMainThreadDiskAccess` | DiskReadViolation in `FirstRunPreferences.setAcknowledged` |

These guard tests passed on the unchanged tree. Each RED is proven by a
recorded mutation, applied and then reverted:

| Test | Mutation | Result |
| --- | --- | --- |
| `IntakeAndListFlowTest.deletingWhileViewingClearsViewerAndReturnsToList` | viewer does not pop when its source disappears | "list must be shown" |
| `IntakeAndListFlowTest.rotatingDuringPreparingStillReachesPreviewOrCancelled` | `delay(2000)` before `getType` in the activity scope | "stranded … last=Preparing" (F-37 reproduced) |
| `IntakeScreenFlowTest.secondShareDuringActiveImportShowsBusyCopy` | Busy mapped to Failed | Busy never reached |
| `SourceListScreenTest.deletingAVisibleRowDoesNotCrashWithRecycledBitmap` | evict (recycle) the thumbnail before deleting | recycled-bitmap crash |

## GREEN and REFACTOR

- 789eaa9 (vault): repositories own `ioDispatcher`, and `unwrap` separates
  envelope failures from Keystore failures.
  - `ArtefactAuthenticator.check` returns Verified, Corrupt, Missing or
    Transient.
  - `readReadyBytes` marks CORRUPT only on Corrupt.
- 689fc01: viewer content state and off-main decode; no thumbnail
  recycling; NEW_TASK and singleTask.
- e2d40fb: the ViewModel owns the intake lookup and open; Cancel while
  Preparing; LaunchedEffect for Cancelled; StrictMode everywhere; first-run
  and diagnostics reads moved off main.
- ebab1e1: the intake preview decodes off main.

## Verification commands

| Command | Result |
| --- | --- |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew :app:connectedDebugAndroidTest` | 68/68; every class under `StrictModeRule` |
| `./gradlew :vault:connectedDebugAndroidTest` | 85/85 |
| `scripts/release-smoke.sh app-debug.apk` (share → preview → save → view → confirmed delete) | `RELEASE_SMOKE_RESULT=pass` |
| `adb logcat -d \| grep -c 'StrictMode policy violation'` after that flow | 0 (0 with org.openlife frames) |
| `scripts/release-smoke.sh` on a release APK of this tree | `RELEASE_SMOKE_RESULT=pass` |

## Deviations from the brief (recorded, not silent)

- **R1:** `prepareImport` is not wrapped in the IO dispatcher. Its provider
  read must stay on the single provider-owned thread (P1-09 owner-thread
  close). Its only caller already runs off main.
- **R6:** the brief says the row should own recycling. But a row cannot
  safely recycle a bitmap the cache may hand to another composition, so
  cached thumbnails are dropped and freed by GC (API 26+). The viewer and
  intake preview, which each own their decode, still recycle it. The threat
  model states the memory-lifetime consequence.
- **R10 Busy:** tested while the first import is preparing. Busy during an
  idle preview needs P1-15's `importInProgress` flag. The deadline
  end-to-end case through `IntakeViewModel` is the existing P1-09 test
  `readDeadlineProducesFailedStateAndReleasesTheDescriptorOnTheOwningThread`.
- **R9:** a runbook check with `dumpsys activity activities` is not run
  here. The JVM flag test and the manifest boundary test cover the
  configuration.

## Observations

- The Compose test rule and ActivityScenario/UiDevice checks do not mix in
  one class: MainActivity was RESUMED but never drawn. Compose-driven intake
  tests therefore live in `IntakeScreenFlowTest`.
- After `adb root`, MediaStore shares from the shell reached OpenLife with
  no provider type, and both old and new code rejected them. `adb unroot`
  restored normal behaviour. Do not run the smoke as root.

## Gaps

- No test asserts which thread performs the bitmap decode; StrictMode
  cannot observe CPU work.
- API 29 results come from CI.
