# P2-01 verification: persisted OCR state in the viewer

Date: 2026-09-24. Branch: `plan/P2-01-persisted-ocr`. Device: `dev36`
(API 36). API 29 evidence comes from CI on the PR.

## RED (fce1e97)

| Test | Failure on the unchanged tree |
| --- | --- |
| `OcrRepositoryTest.observeOcrViewEmitsLatestRevisionSpansAndCorrections` | the stub flow had no matching element |
| `OcrRepositoryTest.observeOcrViewEmitsNullAfterSourceDeletion` | same |
| `ViewerScreenC1Test.correctionIsShownBesideImmutableOcrTextAndReviewChipReflectsState` | no "Corrected by you" or "Review" nodes |
| `ViewerScreenC1Test.runAgainCreatesANewRevisionAndKeepsHistory` | no "Run again" or "History" nodes |
| `OcrPersistenceFlowTest.readyRevisionIsShownAfterViewModelRecreationWithoutRerunning` | could not read the persisted view |
| `OcrViewModelTest` (JVM, 3 tests) | the stub presenter returned `Idle` |

## GREEN (1e80d6c) and REFACTOR (946623a)

- **Database reads.** A new `OcrViewDao` returns revisions newest first. Two
  runs can start in the same millisecond, so ties are broken by insertion
  order (`rowid`); the vault test runs OCR twice back to back to cover this.
  Corrections are read in `createdAt, rowid` order, so the last one per span
  is the latest.
- **The combined view.** `OcrRepository.observeOcrView` combines the latest
  revision, its spans, their latest corrections, a whole-revision correction
  and the history. It emits null when there is no revision, including after
  the source is deleted.
- **The presenter.** `OcrPresenter` is plain Kotlin, so it can be tested on
  the JVM.
  - It derives each source's state from the persisted view plus a transient
    running marker.
  - It starts at `Loading`, never at `Idle`, so "Extract" is never offered
    before the database has answered.
  - It reads only while observed: when nobody is observing, the value returns
    to `Loading`.
  - A run that fails before recording a revision is still explained.
- **Clearing held text.** `clearTransient()` runs on `MainActivity.onStop`
  and on memory trim, replacing the P1-16 `OcrTrim` helper. It bumps a
  generation counter, so a panel that is still visible re-reads its state.
- **Deletion and reset.** `forget` runs when a source is deleted or the vault
  is reset.
- **The ViewModel.** It is now a thin layer over the presenter. Its backend
  awaits the app lock before reading or running (ADR-0004).
- **Changed call sites.** `correct` and `review` take the revision id from the
  rendered state: from the span for corrections, and from the Ready state for
  review.
- **The viewer panel.** It moved to `OcrPanel.kt` and shows:
  - the engine and model version, and the extraction time;
  - the review state;
  - "Corrected by you: …" under each original span, and for the whole
    revision;
  - "Run again", and a History list with metadata only.

  The text stays plain and inert.
- **Test updates.**
  - `ViewerScreenC1Test.deletingTheSource…` and `MainThreadPolicyTest` now
    observe the per-source state instead of reading a map. The deletion test
    also calls `composeRule.waitForIdle()`, because it relies on a
    recomposition to re-read after the source is forgotten.
  - `TrimMemoryPolicyTest`'s OCR half now points to
    `OcrViewModelTest.clearTransientDropsTextAndFlowRepopulates`.
  - `OcrRepository.findSpans` was removed; its one test now reads through the
    DAO.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest :vault:testDebugUnitTest` | pass (`OcrViewModelTest` 3/3) |
| `:vault:connectedDebugAndroidTest` | 117 testcases, all pass (`RecoveryTimingTest` skipped by assumption) |
| `:app:connectedDebugAndroidTest` | 96/96 |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL |

### Manual: OCR survives a process kill (dev36)

The item was imported through the debug-only synthetic provider
(`noise-640x480.jpg`). ML Kit finds no Latin text in noise, so the revision is
READY with no spans. That is enough to show the persisted engine line, the
review state and the history.

```
$ am start MainActivity; I understand; Not now
Status: ok
$ am start SEND content://net.openlifetest.hostileprovider/noise-640x480.jpg (debug-only synthetic provider)
Status: ok
content-desc="Selected image preview"
text="JPEG · 640×480 · 305.0 KB"
text="OpenLife stores its own copy of this image. The original stays with the app you shared it from."
text="Cancel"
content-desc="Saved image thumbnail"
text="Imported Sep 24, 2026 3:35 PM"
content-desc="Saved"
$ (after Save) current screen:
content-desc="Saved image thumbnail"
text="Imported Sep 24, 2026 3:35 PM"
content-desc="Saved"
text="SAVED"
content-desc="Delete"
content-desc="Section divider"
$ tap Done; am start MainActivity; open the item
Status: ok
content-desc="Saved image"
text="Extracted text"
text="Extract text on this device"
text="Extracted text is untrusted and remains separate from the saved image."
content-desc="Section divider"
text="Details"
text="Imported: Sep 24, 2026 3:35 PM"
text="Route: Shared to OpenLife"
text="Integrity: Verified against the saved copy"
content-desc="Verified"
text="VERIFIED"
text="Verification confirms this is the exact copy OpenLife saved. It does not confirm who sent it or that its contents are true."
$ tap "Extract text on this device"
content-desc="Saved image"
text="Extracted text"
text="Engine: mlkit-latin 16.0.1"
text="Extracted: Sep 24, 2026 3:37 PM"
text="Review: Unreviewed"
text="No Latin text was found."
text="Accept text"
text="Reject"
text="Run again"
text="Extracted text is untrusted and remains separate from the saved image."
content-desc="Section divider"
text="Details"
$ tap "Accept text"
text="Review: Accepted"
$ pidof org.openlife (before): 6636
$ input keyevent HOME; am kill org.openlife
$ pidof org.openlife (after kill): 
$ am start MainActivity; open the item
Status: ok
$ pidof org.openlife (new process): 7048
content-desc="Saved image"
text="Extracted text"
text="Engine: mlkit-latin 16.0.1"
text="Extracted: Sep 24, 2026 3:37 PM"
text="Review: Accepted"
text="No Latin text was found."
text="Accept text"
text="Reject"
text="Run again"
text="Extracted text is untrusted and remains separate from the saved image."
content-desc="Section divider"
text="Details"
$ tap "Run again"
text="Engine: mlkit-latin 16.0.1"
text="Review: Unreviewed"
text="History (1)"
text="Extracted · Sep 24, 2026 3:37 PM · mlkit-latin 16.0.1"
```

## Limits

- **Deviation: no `observeLatestRevisionForSource`.** The step asked for
  `observeLatestRevisionForSource` (`LIMIT 1`). History needs every revision
  anyway, so the implementation observes them all, newest first, and takes the
  first.
- **Deviation: test name.** The JVM test keeps the plan's name
  `OcrViewModelTest` but exercises `OcrPresenter`, the logic inside the
  ViewModel, which avoids needing a Main dispatcher on the JVM.
- **Not run manually: real text.** No manual run used an image with real text,
  where corrections would appear on the device. The compose test covers the
  correction rendering.
