# P1-19 — App audit remediation: verification record

Audit: `docs/reviews/2026-11-24-app-audit.md`.
Branch: `plan/P1-19-app-remediation`, based on P2-06.
Date: 2026-11-24.

## Environment and the device gap

This session had **no device**, by owner instruction. The rules were:

- No emulator was started.
- No `connected*AndroidTest` task ran.
- No adb was run against the attached tablet, which belongs to another agent.
- No CI workflow was dispatched.

Every instrumented test below therefore **compiles** (`:app:compileDebugAndroidTestKotlin` passes) but **has not run**. A test that has not run is recorded as a gap. It is never recorded as a pass, and no suspected finding is called confirmed or refuted on the strength of a test that has not run.

Pending command, one class at a time on a booted device:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>
```

API 29 evidence comes from CI's API 29 leg (P0-04) once the branch is pushed. Pushing is an owner action.

## Status key

| Status | Meaning |
| --- | --- |
| **fixed** | The behaviour changed, and a JVM test that failed before the change passes after it. |
| **fixed (code)** | The defect is visible in the cited source, and the change is made. Its proof is an instrumented test that is still pending. |
| **refuted** | Shown not to occur. No finding reached this state here. |
| **gap** | Suspected, and not settled without a device. A test that settles it exists and compiles. |

## Findings

| ID | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| A-01 | R1 | **fixed** | `AppLockState` now measures time away on trips OpenLife starts itself. On return it locks once the trip reaches max(timeout, 60 s), using `EXTERNAL_GRACE_MILLIS` on elapsedRealtime. JVM: `AppLockStateTest.externalTripLongerThanGraceLocksOnReturn`, `.externalTripUsesTheLongerOfTimeoutAndGrace` and `.aDroppedPickerResultCannotLeaveTheLockDisabled` failed in RED (UNLOCKED) and pass in GREEN. `.aShortExternalTripStartedByOpenLifeDoesNotRelock` replaces the old unbounded-exemption test. |
| A-02 | R5, R6 | **fixed** (JVM rules); wiring is a device gap | `ContentTextField` filters the selection toolbar (both `showMenu` overloads) and new context-menu components, blocks clipboard-write shortcuts and `performContextMenuAction(copy/cut)`, sets `autoCorrectEnabled = false`, and ORs `IME_FLAG_NO_PERSONALIZED_LEARNING` into EditorInfo. JVM: `ContentTextFieldPolicyTest` (4) red, then green. Boundary: `SourceEgressBoundaryTest.everyContentTextFieldUsesTheSafeWrapper` failed in RED on the viewer correction field and passes in GREEN. Its built-in mutation (a bare `OutlinedTextField` appended to ViewerScreen's lines) must fail on every run. The reset-word field is the named exemption. Device: `ui.ContentTextFieldTest` (2), pending. |
| A-03 | R7 | **fixed** (policy) | Save runs on `ImportHousekeeping.appScope`. `onCleared` leaves a Saving state to the save. The save settles the slot when no screen remains, and an atomic flag guarantees it settles only once. JVM: `IntakeLifecyclePolicyTest` (2). Device: `IntakeScreenFlowTest.saveThenHomeEndsReadyOrCleanlyCancelledNeverUnknown`, pending. |
| A-04 | R8, R9, R10 | **fixed** (JVM); goldens pending | Every one of the 48 `ColorScheme` roles is now set from the palette (`ColorSchemeCompletenessTest`, red then green). One exemption is pinned to the brief's value: carbon Error `#F2B8B5` equals Material's dark baseline. Typography follows the brief (`TypographyBriefTest`, 3). All 38 bare button calls go through `OpenLifeButton`/`OpenLifeOutlinedButton`/`OpenLifeTextButton`/`OpenLifeFilledTonalButton` at 8 dp (`ButtonShapeSourceTest`). Delete icons use `colorScheme.error`. The dialog golden test `BrandScreenshotInstrumentedTest.deleteDialogMatchesGolden` exists, and its JVM pairing check `BrandScreenshotTest.deleteDialogGoldensArePairedPerProfile` passes. **No dialog golden PNG is committed.** Generation needs a device (`-PgenerateGoldens`) and the CI `generate-goldens` dispatch, so it is a gap; no PNG was fabricated or edited by hand. `ButtonShapeTest` (pixel corner) is pending. **Existing goldens now stale (R10 "regenerate local goldens", not done):** `list-{light,dark}.png`, `viewer-{light,dark}.png` and `first-run-{light,dark}.png` in all three profiles (18 PNGs). The reasons are the empty-state heading, wordmark and first-run title moving to displaySmall, the 8 dp Plex-labelled buttons, and the error-tinted Delete icon. CI's connected leg is expected to fail those comparisons until they are regenerated. `brand-components-*` is expected to be unaffected: the stamp, FoldedCornerCard (surfaceVariant, bodyLarge) and perforation use unchanged tokens. That is unverified until a device run. |
| A-06 | R13 | **gap** | The suspicion is that Compose pauses its frame clock at ON_STOP, so `onDispose → recycle()` waits for ON_START. A bytecode reading of `WindowRecomposer` shows its ON_STOP handler pauses the frame clock for awaiters. That supports the suspicion but does not prove a retained bitmap, so no holder was built. Probe: `DecodedBitmapProbe` (debug-only weak tracking). Test: `MultiWindowScrubTest.decodedBitmapsAreReleasedWhileHidden`, pending. If it fails on a device, R13's holder cleared from `onStop` is the follow-up. |
| A-07 | R2 | **gap**; R1 is the backstop | `AppLockFlowTest.droppedPickerResultDoesNotLeaveTheLockDisabled` is pending. Whatever it shows, R1's bound means a stale flag can no longer keep the lock off for a trip longer than 60 s (JVM test above). The `onStart` flag clear that R2 makes conditional on confirmation is not added. **Residual:** if a result is dropped, `externalActivity` stays true until the next `onUnlocked()`. Every later ordinary trip to the background therefore gets the 60 s grace, so a user on a 0 s timeout effectively has a 60 s lock until they next authenticate. |
| A-08 | R11, R12 | **fixed** (code); semantics tests pending | `PerforationDivider` uses `clearAndSetSemantics {}`, and the "Section divider" string is removed. Span actions get spoken names that include `evidenceSpanPreview` (new strings `viewer_*_for`). Outline now has its own token: light `#7D776B`, ≥ 3.42:1 on every paper surface; dark `#857F75`, ≥ 3.56:1 on every carbon surface (`PaletteContrastTest.outlineRoleMeetsThreeToOneOnPaperAndCarbon`, red at 1.35:1, now green). Rule stays as `outlineVariant`, and the NavHost divider moved to it. StampBadge (R12), fixed (code): the source shows the label on two nodes, the Box's `contentDescription` and its child `Text`. It now uses `clearAndSetSemantics { contentDescription }`. Device: `AccessibilitySemanticsTest.perforationDividerIsNotAnnounced`, `.ocrRowActionsNameTheirSpan` and `.stampBadgeLabelIsAnnouncedOnce`, pending. Overlay part, by code reading of P2-06: `ZoomableEvidenceImage.kt:339-349` makes each region a semantics node with a content description and an `onClick` action. P2-06's `EvidenceNavigationTest` covers it on a device. |
| A-10 | R14 | **gap** | Both pops are in the source (`ViewerDestination`'s `LaunchedEffect` on a null source, and `onClose` after delete). Whether both run depends on dispatcher ordering, which reading cannot settle. A delay seam (`SourceListViewModel.deleteResultDelayMillisForTest`) forces the flow to win. Test: `NavigationBackTest.deleteFromViewerKeepsListOnTheBackStack`, pending. No fix is made until it is confirmed; navigation is P1-20's. |
| A-11 | R15 | **fixed (code)** | Confirmed by reading the source. The state lived in `remember` and the work in `rememberCoroutineScope` (`ResetVaultFlow.kt`, before), and `MainActivity` declares no `configChanges`, so rotation recreates it. Both now live in `SettingsController`, held by `SettingsViewModel`. Reset completion is state, consumed by a `LaunchedEffect`. JVM: `SettingsControllerTest` (2). Device: `settings.SettingsRotationTest`, pending. AppLockSettings state was not moved; it is a persisted preference with no in-flight work. |
| A-12 | R4 | **gap** | `AppLockFlowTest.deviceCredentialUnlockOnApi29KeepsThePendingShare` sets a PIN with `locksettings` and skips itself unless the device runs API 29. It needs CI's API 29 leg. `noHistory` is unchanged. |
| A-13 | R16 | **fixed (code)** | Confirmed by reading the source. `forgetDeletedSources` returned at once unless the vault was Ready at ViewModel creation, and it never followed a new vault (`OcrViewModel.kt`, before). Now `OpenLifeApp.readyVault`, a StateFlow updated whenever `cachedAccess` changes, feeds `forgetSourcesLeavingTheList`, which uses `collectLatest`. JVM: `DeletedSourceTrackerTest` (red: nothing forgotten). Device: `OcrPersistenceFlowTest.ocrTextIsForgottenForSourcesDeletedAfterVaultReset`, pending. |
| A-14 | R17 | **fixed** | `EXTRA_OPEN_SOURCE_ID` is honoured only with a valid single-use nonce issued by `OpenLifeApp.openSourceNonce`, which IntakeActivity's "Open existing" supplies. The id is parsed first, so a malformed request does not burn the nonce. JVM: `OpenExistingIntentTest.openSourceExtraWithoutValidNonceIsIgnored`. Four existing androidTests now pass the nonce. |
| A-15 | R18 | **fixed** (comment and doc) | The `IntakeIntentValidator` comment and THREAT_MODEL now say the grant flag is set by the caller and is not a control. The defence is that OpenLife holds no media or storage permission. Behaviour is unchanged. |
| A-16 | R19 | **fixed** | Confirmed by JVM test: `content://x@org.openlife.y/a` validated as Valid in RED. The check now compares the host after removing userinfo and port, ignoring case. `IntakeIntentValidatorTest.ownAuthorityCheckCannotBeBypassedWithUserinfoOrPort` is green. Deviation: userinfo is not rejected outright, because cross-profile shares arrive as `10@authority`; the test keeps `10@com.example.provider` valid. |
| A-17 | R20 | **fixed** | `VaultFailureDiagnostics` now uses a string per cause, a plural for the count, and `ListFormatter`. Verify-all joins its counts with `ListFormatter`. `IntakeKind` is compared by enum. `describeForTest` moved to androidTest. Every `*ForTest` seam is `internal` with `@VisibleForTesting` (`TestSeamVisibilityTest`). `NoHardcodedUiStringsTest.stringTemplatesInTextCallsAreFlagged` flags templates, `joinToString(", ")` and `Text(... + "...")`; it has built-in mutation self-checks and was red on `OpenLifeApp.kt` in RED. |
| A-18 | R3 | **fixed** (comment and doc) | The AppLockState KDoc, ADR-0004 and THREAT_MODEL now describe the ~700 ms `ProcessLifecycleOwner` stop, the scrub in each activity's `onStop`, and `FLAG_SECURE`. |

A-05 (structural navigation) and A-09 (test quality, apart from the
AppLockStateTest case) belong to P1-20.

## Addition from the P1-18 review: OutOfMemoryError on every decode path

| Path | Guard | JVM test |
| --- | --- | --- |
| App decodes: intake preview, viewer image, list thumbnail (all go through `SampledBitmapDecoder.decode`) | `decodeGuardingMemory`: an OOM gives null, which callers already show as unreadable or no thumbnail. If orienting fails, the sampled bitmap is recycled. | `BitmapOomGuardTest` (3) |
| OCR (`OcrBitmapPreparer.prepare`) | `prepareGuarded`: an OOM becomes `OcrDecodeException`, which the run records as ENGINE_FAILURE. The decoded bitmap is recycled. | `OcrBitmapMemoryTest` (4) |
| Import sample check (`ImportRepository` → `BitmapSampler.canSample`) | `checkSample(...).refusal()`: an OOM gives `PrepareResult.Failed` with nothing staged. A refused decode is still CORRUPT_CONTENT. | `SampleCheckTest` (2) |

## Existing tests changed

| Test | Reason |
| --- | --- |
| `AppLockStateTest.anExternalActivityStartedByOpenLifeDoesNotRelock` | Removed. It wrote down the unbounded exemption (R1). The short-trip case is kept as `aShortExternalTripStartedByOpenLifeDoesNotRelock`. |
| `MultiWindowScrubTest`, `NavigationBackTest`, `IntakeAndListFlowTest`, `AppLockFlowTest` | The open-source extra now carries the nonce (R17). |
| `AppLockFlowTest`, `IntakeAndListFlowTest` | They import `currentStatusForTest`, which moved to androidTest (R20). |

## Commands run (real output)

| Commit | Command | Result |
| --- | --- | --- |
| RED 8676b1e | `./gradlew :app:testDebugUnitTest :vault:testDebugUnitTest :app:compileDebugAndroidTestKotlin :vault:compileDebugAndroidTestKotlin --continue` | Both androidTest compiles succeeded. app: 114 tests, 26 failed. vault: 134 tests, 5 failed. Every failure was intended. |
| GREEN 42c1d55 | `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL. app 114/114, vault 134/134. |
| REFACTOR 0ca8a6e | the same command | BUILD SUCCESSFUL in 5m 19s. app 114/114, vault 134/134. |
| 0ca8a6e | `scripts/plan-check.sh` | steps=82 errors=0 |
| 42c1d55 tree | `bash scripts/tests/run.sh` | 47 passed, 0 failed |
| none | `connectedDebugAndroidTest` (any module) | **Not run**: no device, by owner instruction. |
