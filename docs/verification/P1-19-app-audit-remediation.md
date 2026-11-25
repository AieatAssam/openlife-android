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

### Before running on a physical device

The connected suite is written for a disposable test install. Run it only
against a **debug build on a device with no real OpenLife data**:

- It resets and deletes vault content, and writes the app-lock policy
  (`AppLockFlowTest`, `SettingsRotationTest`, the intake and OCR flow tests).
- The debug build's applicationId is `org.openlife`, with no suffix. Installing
  it replaces any OpenLife install on the device, so the device must hold no
  OpenLife install with real data.
- `ContentTextFieldTest` writes a sentinel to the device clipboard. It saves
  the previous clip once the test window has focus and restores it afterwards
  when that read succeeded. If the read returned null (empty, or unreadable)
  the sentinel is left and the clipboard is never cleared.
- `AppLockFlowTest.deviceCredentialUnlockOnApi29KeepsThePendingShare` sets and
  then clears a device PIN. It runs only on an emulator (`ro.kernel.qemu` or
  `ro.boot.qemu` = 1, or `Build.HARDWARE` ranchu/goldfish) **and** only when
  `-Pandroid.testInstrumentationRunnerArguments.allowDevicePinChange=true` is
  passed. On a physical device it is always skipped and never touches the
  screen lock.

On-device checks still owed (reviewer's list, second round):

1. `ContentTextFieldTest`, plus a manual long-press on the correction field
   with a Translate (ACTION_PROCESS_TEXT) app installed, and a right-click
   with a mouse: only Paste and Select all may appear.
2. `adb shell dumpsys autofill` while the correction field is focused. This
   is read-only; do not change the device's autofill service. Expected: no
   OpenLife session, or none carrying the field's value.
3. Photo Picker round trip with the lock timeout at 0: return in under 60 s
   (no lock), over 60 s (locked), and an immediate Back from the picker.
4. Rotate the device while the biometric prompt is open.

## Status key

| Status | Meaning |
| --- | --- |
| **fixed** | The behaviour changed, and a JVM test that failed before the change passes after it. |
| **implemented; unverified (gap — pending physical-device run)** | The defect is visible in the cited source and the change is made, but nothing that has run proves it. Its proof is an instrumented test that is still pending. |
| **refuted** | Shown not to occur. No finding reached this state here. |
| **gap** | Suspected, and not settled without a device. A test that settles it exists and compiles. |

## Findings

| ID | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| A-01 | R1 | **fixed** | `AppLockState` now measures time away on trips OpenLife starts itself. On return it locks once the trip reaches max(timeout, 60 s), using `EXTERNAL_GRACE_MILLIS` on elapsedRealtime. JVM: `AppLockStateTest.externalTripLongerThanGraceLocksOnReturn`, `.externalTripUsesTheLongerOfTimeoutAndGrace` and `.aDroppedPickerResultCannotLeaveTheLockDisabled` failed in RED (UNLOCKED) and passed in GREEN. The last was rewritten in review Q-02; the RED observation was of the earlier version. `.aShortExternalTripStartedByOpenLifeDoesNotRelock` replaces the old unbounded-exemption test. |
| A-02 | R5, R6, review Q-01 | **fixed** (JVM rules); wiring is a device gap | See "Review round" below for what is closed and how it was established from the compiled Compose classes. `ContentTextField` provides a write-dropping `LocalClipboard` (`ReadOnlyClipboard`), keeps only Paste and Select all in the context menu (allow-list), blocks clipboard-write shortcuts and `performContextMenuAction(copy/cut)`, sets `autoCorrectEnabled = false`, and ORs `IME_FLAG_NO_PERSONALIZED_LEARNING` into EditorInfo. Smart selection is off app-wide. JVM: `ContentTextFieldPolicyTest` (4). Boundary: `SourceEgressBoundaryTest.everyContentTextFieldUsesTheSafeWrapper` and `.clipboardReferencesAreConfinedToTheReadOnlyField`, each with built-in mutations. The reset-word field is the named exemption. Device: `ui.ContentTextFieldTest` (2), pending. |
| A-03 | R7 | **fixed** (policy) | Save runs on `ImportHousekeeping.appScope`. `onCleared` leaves a Saving state to the save. The save settles the slot when no screen remains, and an atomic flag guarantees it settles only once. JVM: `IntakeLifecyclePolicyTest` (2). Device: `IntakeScreenFlowTest.saveThenHomeEndsReadyOrCleanlyCancelledNeverUnknown`, pending. |
| A-04 | R8, R9, R10 | **fixed** (JVM); goldens pending | Every one of the 48 `ColorScheme` roles is now set from the palette (`ColorSchemeCompletenessTest`, red then green). One exemption is pinned to the brief's value: carbon Error `#F2B8B5` equals Material's dark baseline. Typography follows the brief (`TypographyBriefTest`, 3). All 38 bare button calls go through `OpenLifeButton`/`OpenLifeOutlinedButton`/`OpenLifeTextButton`/`OpenLifeFilledTonalButton` at 8 dp (`ButtonShapeSourceTest`). Delete icons use `colorScheme.error`. The dialog golden test `BrandScreenshotInstrumentedTest.deleteDialogMatchesGolden` exists, and its JVM pairing check `BrandScreenshotTest.deleteDialogGoldensArePairedPerProfile` passes. **No dialog golden PNG is committed.** Generation needs a device (`-PgenerateGoldens`) and the CI `generate-goldens` dispatch, so it is a gap; no PNG was fabricated or edited by hand. `ButtonShapeTest` (pixel corner) is pending. **Existing goldens now stale (R10 "regenerate local goldens", not done):** `list-{light,dark}.png`, `viewer-{light,dark}.png` and `first-run-{light,dark}.png` in all three profiles (18 PNGs). The reasons are the empty-state heading, wordmark and first-run title moving to displaySmall, the 8 dp Plex-labelled buttons, and the error-tinted Delete icon. **On any device run, these 18 comparisons and the two missing delete-dialog goldens (per profile) will fail until the goldens are regenerated on that device; R10 is not done.** Review Q-03 also changed the primary role (below), which changes every golden that shows a primary button, a focused field or the Saved stamp. `brand-components-*` is expected to be unaffected: the stamp, FoldedCornerCard (surfaceVariant, bodyLarge) and perforation use unchanged tokens. That is unverified until a device run. |
| A-06 | R13 | **gap** | The suspicion is that Compose pauses its frame clock at ON_STOP, so `onDispose → recycle()` waits for ON_START. A bytecode reading of `WindowRecomposer` shows its ON_STOP handler pauses the frame clock for awaiters. That supports the suspicion but does not prove a retained bitmap, so no holder was built. Probe: `DecodedBitmapProbe` (debug-only weak tracking). Test: `MultiWindowScrubTest.decodedBitmapsAreReleasedWhileHidden`, pending. If it fails on a device, R13's holder cleared from `onStop` is the follow-up. |
| A-07 | R2, review Q-02 | **fixed** (JVM); device gap | Review Q-02: `AppLockState.onForeground()` now clears `externalActivity` first. Returning to the foreground ends any trip; the trip's own background period was already classified in `onBackground`, so the legitimate round trip keeps its grace, and a late or missing result callback cannot leave the flag set (fails closed). JVM: `AppLockStateTest.aDroppedPickerResultCannotLeaveTheLockDisabled` (dropped result, then an ordinary 5 s background with timeout 0 is LOCKED), `.aPickerRoundTripKeepsItsGraceWhenTheResultArrivesAfterTheReturn`, `.aDroppedResultWhileTheLockWasOffDoesNotExemptItOnceEnabled`. Written but not run in this round (no Gradle). The earlier residual (a 0 s timeout acting as 60 s until the next unlock) is closed. **Remaining residual:** a return within ProcessLifecycleOwner's ~700 ms stop delay dispatches no foreground event, so a dropped result plus such a fast return leaves the flag set until the next return, result or unlock. Device: `AppLockFlowTest.droppedPickerResultDoesNotLeaveTheLockDisabled`, rewritten to need no clock seam, pending. It never launches a picker, so it cannot confirm or refute how often a real result is dropped; it checks the state machine under real ProcessLifecycleOwner events. |
| A-08 | R11, R12 | outline contrast **fixed** (JVM); semantics changes **implemented; unverified (gap — pending physical-device run)** | `PerforationDivider` uses `clearAndSetSemantics {}`, and the "Section divider" string is removed. Span actions get spoken names that include `evidenceSpanPreview` (new strings `viewer_*_for`). Outline now has its own token: light `#7D776B`, ≥ 3.42:1 on every paper surface; dark `#857F75`, ≥ 3.56:1 on every carbon surface (`PaletteContrastTest.outlineRoleMeetsThreeToOneOnPaperAndCarbon`, red at 1.35:1, now green). Rule stays as `outlineVariant`, and the NavHost divider moved to it. StampBadge (R12), implemented, unverified: the source shows the label on two nodes, the Box's `contentDescription` and its child `Text`. It now uses `clearAndSetSemantics { contentDescription }`. Device: `AccessibilitySemanticsTest.perforationDividerIsNotAnnounced`, `.ocrRowActionsNameTheirSpan` and `.stampBadgeLabelIsAnnouncedOnce`, pending. Overlay part, by code reading of P2-06: `ZoomableEvidenceImage.kt:339-349` makes each region a semantics node with a content description and an `onClick` action. P2-06's `EvidenceNavigationTest` covers it on a device. |
| A-10 | R14 | **gap** | Both pops are in the source (`ViewerDestination`'s `LaunchedEffect` on a null source, and `onClose` after delete). Whether both run depends on dispatcher ordering, which reading cannot settle. A gate seam (`SourceListViewModel.beforeDeleteResultForTest`, review Q-07/Q-08; it replaced a fixed delay) holds the delete callback until the test releases it after the flow-driven pop, so the test needs no sleep. The seam stays until P1-20 (noted in plan.yaml). Test: `NavigationBackTest.deleteFromViewerKeepsListOnTheBackStack`, pending. No fix is made until it is confirmed; navigation is P1-20's. |
| A-11 | R15 | **implemented; unverified (gap — pending physical-device run)** | Confirmed by reading the source only. The state lived in `remember` and the work in `rememberCoroutineScope` (`ResetVaultFlow.kt`, before), and `MainActivity` declares no `configChanges`, so rotation recreates it. Both now live in `SettingsController`, held by `SettingsViewModel`. Reset completion is state, consumed by a `LaunchedEffect`. JVM: `SettingsControllerTest` (2). Device: `settings.SettingsRotationTest`, pending. AppLockSettings state was not moved; it is a persisted preference with no in-flight work. |
| A-12 | R4 | **gap** | `AppLockFlowTest.deviceCredentialUnlockOnApi29KeepsThePendingShare` sets a PIN with `locksettings`. It skips itself unless the device runs API 29, is an emulator, and the run passes `allowDevicePinChange=true` (review, device safety). It needs CI's API 29 emulator leg with that argument. **No workflow passes `allowDevicePinChange=true` today**, so the test is skipped everywhere until the owner adds that runner argument to the manual-dispatch API 29 emulator leg. `noHistory` is unchanged. |
| A-13 | R16 | **implemented; unverified (gap — pending physical-device run)** | Confirmed by reading the source only. `forgetDeletedSources` returned at once unless the vault was Ready at ViewModel creation, and it never followed a new vault (`OcrViewModel.kt`, before). Now `OpenLifeApp.readyVault`, a StateFlow updated whenever `cachedAccess` changes, feeds `forgetSourcesLeavingTheList`, which uses `collectLatest`. JVM: `DeletedSourceTrackerTest` (red: nothing forgotten). Device: `OcrPersistenceFlowTest.ocrTextIsForgottenForSourcesDeletedAfterVaultReset`, pending. |
| A-14 | R17 | **fixed** | `EXTRA_OPEN_SOURCE_ID` is honoured only with a valid single-use nonce issued by `OpenLifeApp.openSourceNonce`, which IntakeActivity's "Open existing" supplies. The id is parsed first, so a malformed request does not burn the nonce. JVM: `OpenExistingIntentTest.openSourceExtraWithoutValidNonceIsIgnored`. Four existing androidTests now pass the nonce. |
| A-15 | R18 | **fixed** (comment and doc) | The `IntakeIntentValidator` comment and THREAT_MODEL now say the grant flag is set by the caller and is not a control. The defence is that OpenLife holds no media or storage permission. Behaviour is unchanged. |
| A-16 | R19 | **fixed** | Confirmed by JVM test: `content://x@org.openlife.y/a` validated as Valid in RED. The check now compares the host after removing userinfo and port, ignoring case. (Review Q-05: the code comment now says the platform strips only the `userId@` prefix; the port is removed by this check, not by the platform.) `IntakeIntentValidatorTest.ownAuthorityCheckCannotBeBypassedWithUserinfoOrPort` is green. Deviation: userinfo is not rejected outright, because cross-profile shares arrive as `10@authority`; the test keeps `10@com.example.provider` valid. |
| A-17 | R20 | **fixed** | `VaultFailureDiagnostics` now uses a string per cause, a plural for the count, and `ListFormatter`. Verify-all joins its counts with `ListFormatter`. `IntakeKind` is compared by enum. `describeForTest` moved to androidTest. Every `*ForTest` function, `val` or `var` is `internal` with `@VisibleForTesting`, or a private backing field (`TestSeamVisibilityTest`, extended in review Q-07 with self-checks). `NoHardcodedUiStringsTest.stringTemplatesInTextCallsAreFlagged` flags templates, `joinToString(", ")` and `Text(... + "...")`; it has built-in mutation self-checks and was red on `OpenLifeApp.kt` in RED. |
| A-18 | R3 | **fixed** (comment and doc) | The AppLockState KDoc, ADR-0004 and THREAT_MODEL now describe the ~700 ms `ProcessLifecycleOwner` stop, the scrub in each activity's `onStop`, and `FLAG_SECURE`. |

A-05 (structural navigation) and A-09 (test quality, apart from the
AppLockStateTest case) belong to P1-20.

The A-11 and A-13 production changes were made on code-reading confirmation,
without first observing the confirm-first instrumented test fail. The
coordinator accepted this on 2026-11-25 because both fixes are small and safe
whether or not the device test would have failed.

## Addition from the P1-18 review: OutOfMemoryError on every decode path

| Path | Guard | JVM test |
| --- | --- | --- |
| App decodes: intake preview, viewer image, list thumbnail (all go through `SampledBitmapDecoder.decode`) | `decodeGuardingMemory`: an OOM gives null, which callers already show as unreadable or no thumbnail. If orienting fails, the sampled bitmap is recycled. | `BitmapOomGuardTest` (3) |
| OCR (`OcrBitmapPreparer.prepare`) | `prepareGuarded`: an OOM becomes `OcrDecodeException`, which the run records as ENGINE_FAILURE. The decoded bitmap is recycled. | `OcrBitmapMemoryTest` (4) |
| Import sample check (`ImportRepository` → `BitmapSampler.canSample`) | `checkSample(...).refusal()`: an OOM gives `PrepareResult.Failed` with nothing staged. A refused decode is still CORRUPT_CONTENT. | `SampleCheckTest` (2) |

## Review round (PR #26, Q-01..Q-08), 2026-11-25

Nothing in this round was compiled or run locally: another agent held the
host's one Gradle slot, so the coordinator ran no Gradle here and hosted CI
(build, detekt, lint, JVM tests; no emulator) is the first check. Every JVM
test named below is **written, not yet run**.

### Q-01: what a content field can still leak, read from the compiled classes

Compose BOM 2026.08.00 pins foundation, ui 1.12.0 and material3 1.4.0. The Gradle
cache also holds foundation-android and ui-android 1.12.1, which a transitive
dependency may resolve instead. Both were read with `javap -c` from the cached
`foundation.aar` and `ui.aar` (`~/.gradle/caches/modules-2/files-2.1/androidx.compose.{foundation,ui}/{foundation,ui}-android/1.12.{0,1}`), and agree on every point:

| Fact | Where (class) |
| --- | --- |
| The new context menu is on by default: `isNewContextMenuInitiallyEnabled()` returns true. With it on, `showSelectionToolbar` calls `ToolbarRequester.show()` and never `LocalTextToolbar`, so the old `NoClipboardWriteTextToolbar` wrapper was dead code. It is removed. | `ComposeFoundationFlags_androidKt`, `TextFieldSelectionManager.showSelectionToolbar$foundation` |
| Smart selection defaults to true (`isSmartSelectionEnabled`, an `@ExperimentalFoundationApi` flag) and is read in `CoreTextField` at composition. `OpenLifeApp.onCreate` sets it false before any UI. | `ComposeFoundationFlags.<clinit>`, `CoreTextFieldKt` |
| Menu items are keyed: `TextContextMenuKeys` {Cut, Copy, Paste, SelectAll, Autofill}, `ProcessTextKey(id)` for ACTION_PROCESS_TEXT apps, and `TextContextMenuTextClassificationItem` for classification actions. Both the floating toolbar and the right-click menu build their data through `collectTextContextMenuData`, which applies `filterTextContextMenuComponents`. The filter is now an allow-list of Paste and Select all. | `contextmenu.data.*`, `TextContextMenuToolbarHandlerNode`, `TextContextMenuGestureNode$ClickTextContextMenuDataProvider` |
| Material3 `OutlinedTextField(String, …)` calls `BasicTextField(String, …)`, i.e. the `CoreTextField` path. | `material3.OutlinedTextFieldKt` |
| `CoreTextField` reads `LocalClipboard` and hands it to `TextFieldSelectionManager.setClipboard`. `copy()` and `cut()` write only through `Clipboard.setClipEntry` on that object. Nothing in foundation reads `LocalClipboardManager`. `grep -a -l setPrimaryClip` over the extracted foundation 1.12.0/1.12.1, ui 1.12.0/1.12.1 and material3 1.4.0 classes hits only ui's `AndroidClipboardManager.class`, so nothing else writes through the real manager that `ReadOnlyClipboard.clipboardManager` exposes. | `CoreTextFieldKt`, `TextFieldSelectionManager$copy$1`, `$cut$1`, `AndroidClipboardManager` |
| The keyboard's `performContextMenuAction(copy/cut/paste)` is turned into synthesized `KEYCODE_COPY` (278), `KEYCODE_CUT` (277) and `KEYCODE_PASTE` (279) key events. | `text.input.internal.RecordingInputConnection` |
| Paste availability calls `ClipboardUtils.hasText(Clipboard)`, which needs an `AndroidClipboard` (it throws `IllegalArgumentException` otherwise) and reads only `getPrimaryClipDescription()`. `ReadOnlyClipboard` therefore implements `AndroidClipboard` (marked `@VisibleForTesting` in ui 1.12, hence `@SuppressLint("VisibleForTests")`). | `ClipboardUtils`, `AndroidClipboard_androidKt.getNativeClipboardManager` |
| The accessibility delegate reads the old `ClipboardManager.hasText()` only, to offer ACTION_PASTE. | `AndroidComposeViewAccessibilityDelegateCompat` |

What is closed for `ContentTextField`:

- **Every clipboard write is dropped.** This covers menu Copy/Cut, Ctrl+C/X/Insert, Shift+Delete, the Copy/Cut keys, the keyboard's copy/cut request and the accessibility CopyText/CutText actions. They all end in `setClipEntry` on the field's `LocalClipboard`, which is `ReadOnlyClipboard`. Paste still reads the platform clipboard.
- **The menu shows only Paste and Select all.** Copy, Cut, Autofill, process-text apps (Translate and similar) and text-classification actions (Call, Map) are not offered.
- **Smart selection is off.** Selected text is not sent to the system TextClassifier.
- **No personalised learning is requested and autocorrect is off.**

What remains:

- **The keyboard sees typed text.** `IME_FLAG_NO_PERSONALIZED_LEARNING` is only a request, and a keyboard that ignores it can learn from what is typed.
- **A screen reader reads the field aloud**, as it must.
- **An accessibility "Cut" still removes the selected text from the field**, though nothing reaches the clipboard. Keyboard and shortcut Cut are refused before they reach the field, so they delete nothing.
- **The ACTION_PROCESS_TEXT intent query** is no longer reachable from this field's menu, because those items are filtered out.

JVM: `ContentTextFieldPolicyTest.theContextMenuKeepsOnlyPasteAndSelectAll` (ProcessTextKey, Autofill, Copy, Cut and an unknown key are dropped) and `.theFieldClipboardDropsWritesAndKeepsReads`. Device: `ContentTextFieldTest.correctionFieldOffersPasteButNoCopyOrCutAndNeverWritesTheClipboard` has a positive control (Paste is offered), exercises `performSemanticsAction(CopyText)` and the shortcuts, and saves and restores the device clipboard. It is pending.

### Q-02: A-07 residual

See the A-07 row. Walkthrough of the legitimate round trip, in platform order:

1. `beginExternalActivity()`.
2. Activity stops; ~700 ms later the process stops and `onBackground()` records `backgroundedOnExternalTrip = true`.
3. The process starts again and `onForeground()` clears the flag and applies max(timeout, 60 s).
4. The result callback's `endExternalActivity()` is a no-op.

A pick faster than the stop delay produces no background or foreground event, so the flag is cleared by step 4 as before. Seam removed: `AppLockController.clockOffsetForTest` and `advanceClockForTest`, because the rewritten device test no longer needs a clock.

### Q-03: primary text contrast

Brand Vermilion `#C8412B` measured 4.39:1 on Paper, 4.06:1 on PaperHigh (the dialog container) and 3.57:1 on PaperDim. In dark, inversePrimary on inverseSurface was 4.09:1 and DarkVermilion was 3.80:1 on surfaceBright.

Material draws `primary` as text in many places, so the role now uses new tokens:

- **`VermilionInk` `#A83420` (light).** At least 4.77:1 on every paper surface role, 5.14:1 on primaryContainer, and RaisedPaper on it is 6.45:1.
- **`DarkVermilionInk` `#EE7F6A` (dark).** At least 4.87:1 on every carbon surface role and 5.25:1 on primaryContainer, and onPrimary on it is 6.92:1.
- **Not claimed (review U-03):** light secondaryContainer 4.35:1, dark tertiaryContainer 3.48:1, dark errorContainer 4.00:1, dark attentionContainer 4.49:1. App code does not draw on those containers; `PaletteContrastTest.otherContainersAreUsedOnlyThroughTheTheme` is the tripwire.

inversePrimary swaps between the two: 6.53:1 light and 5.46:1 dark. Brand Vermilion stays for the launcher icon and the surface tint. VISUAL_IDENTITY §4 is amended with the reason. JVM: `PaletteContrastTest.primaryTextReachesAaOnEverySurfaceItCanSitOn` covers light and dark, 11 surface or container roles each, plus inverse and onPrimary. It also has a self-check that brand Vermilion fails on the dialog container. The values were computed with the WCAG formula in python3; the test itself has not run.

### Q-06 and Q-07: boundary tests and seams

- **`SourceEgressBoundaryTest`.** It now also catches:
  - fully qualified calls
  - `SearchBar`, `DockedSearchBar` and `SearchBarDefaults.InputField`
  - `BasicSecureTextField`
  - `::TextField` references
  - aliased imports
  - a misplaced exemption: the exemption now applies only to `OutlinedTextField(` directly under the marker comment with `value = word,` directly below it

  Built-in mutations cover each bypass, including a second field placed next to the marker. A new `clipboardReferencesAreConfinedToTheReadOnlyField` allows the platform clipboard manager only on `ReadOnlyClipboard`'s one read line, and `LocalClipboard`/`setClipEntry`/`setPrimaryClip` only in ContentTextField.kt. It has three mutations.
- **`ButtonShapeSourceTest`.** It catches `androidx.compose.material3.Button(`, references and aliased imports. `theCheckCatchesEveryBypass` holds the self-checks.
- **`TestSeamVisibilityTest`.** It now also matches `val`/`var …ForTest`. A private backing field is allowed; a private function is not. Self-checks are included.
- **Python replication.** Each new regex was copied into python3 and run over the current tree before commit: no violations.
- **`SourceListViewModel.beforeDeleteResultForTest` stays until P1-20.** Removing it needs an injectable repository or dispatcher in the ViewModel factory, which is P1-20's ViewModel-scoping work. It is recorded in P1-20's notes.

### Q-08: device test hygiene

- **`OcrPersistenceFlowTest`.** It creates its OcrViewModels through `ViewModelStore` + `ViewModelProvider` and clears them in `@After`.
- **`NavigationBackTest.deleteFromViewerKeepsListOnTheBackStack`.** The fixed 3 s sleep is replaced by a `CompletableDeferred` gate. The test releases the gate after the flow-driven pop, then `runOnMainSync {}` and `waitForIdleSync()`.

## Second review round (U-01..U-04)

### U-01: autofill exported field text

Read with `javap -c -p` from Compose ui 1.12.0 (the pinned version) and
1.12.1 (the version a transitive dependency may resolve), foundation 1.12.1:

| Fact | Where (class) |
| --- | --- |
| `getImportantForAutofill()` returns 1 (YES) unconditionally, so the flag cannot be cleared on the Compose view itself. | `ui.platform.AndroidComposeView` |
| The constructor calls `view.setImportantForAutofill(1)`; it exists when `autofillSupported()` (SDK ≥ 26). | `ui.autofill.AndroidAutofillManager` |
| `onFocusChanged` calls `notifyViewEntered`/`notifyViewExited` only when `isAutofillable(config)`; `notifyAutofillManager` sends `notifyValueChanged` with the text only when the content data type is Text. | `AndroidAutofillManager` |
| `isAutofillable` is false when `ContentDataType` is `None`. `isRelatedToAutofill` decides which nodes enter the structure. | `AndroidAutofillManager_androidKt` |
| `populate` writes `setAutofillValue(text)` from EditableText regardless of data type, and calls `setDataIsSensitive(true)` when `IsSensitiveData` is true. | `PopulateViewStructure_androidKt` |
| Semantics are merged tail to head over the node chain, so an outer (head) modifier overrides the core field's own `contentDataType = Text`. | `node.LayoutNode` (`calculateSemanticsConfiguration`) |
| `CoreTextField` puts the caller's modifier at the head of the root box's chain. | foundation `text.CoreTextFieldKt` |
| A Compose `Dialog` is its own window with its own `AndroidComposeView` (`DialogLayout` via `setContentView`); `Popup` adds a view through WindowManager. | `ui.window.DialogWrapper`, `ui.window.PopupLayout` |

Change (one helper, `ui/SecureWindow.kt`):

- `applySecureWindow()` (every Activity) now also calls
  `excludeWindowFromAutofill(window.decorView)`, which sets the window root to
  `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`. By the platform contract
  (`View.isImportantForAutofill()`), every view below it is then not important.
- `ContentTextField` applies the same helper to its own window
  (`LocalView.current`), which covers the correction dialog, and puts
  `contentDataType = ContentDataType.None` and `isSensitiveData = true` at the
  head of its modifier.
- Popups: none hosts a content field (only menus with fixed labels), so they
  are not covered and hold no content.

Status: **implemented; unverified (gap — pending physical-device run).**
JVM tripwire: `SecureWindowTest.everyActivityExcludesItsWindowFromAutofill`.
Instrumented (compiles on CI, not run): `AutofillExclusionTest`. Read-only
device check: `adb shell dumpsys autofill` while the field is focused.

Not covered: an autofill service that requests views not important for
autofill (`FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`) and any platform handling not
read here. The IME residual is wider than "what is typed": the keyboard can
read the whole prefilled text through the InputConnection.

### U-02: boundary tests

All three scans now say in their KDoc that they are tripwires, not proofs,
and each new pattern has a self-check sample.

- `SourceEgressBoundaryTest.everyNewEgressPatternIsReported`: AndroidView
  `EditText`/`TextView` and `setTextIsSelectable`, `SelectionContainer`,
  backticked composable names and references, `import android.util.*` and
  `Log`, `println`, `printStackTrace`, `System.out/err`, `Intent.ACTION_SEND`,
  the `"android.intent.action.SEND"` literal, `ShareCompat`, `CreateDocument`
  and `createChooser`.
- `ButtonShapeSourceTest`: floating action buttons are filled-container
  buttons, so they take the 8 dp shape (VISUAL_IDENTITY §6). The list FAB now
  uses `OpenLifeExtendedFloatingActionButton`. Icon buttons are excluded: they
  draw no container, only a circular state layer. This changes the list
  screen's FAB corner from 16 dp to 8 dp; its golden was already stale and is
  not hand-edited.
- `TestSeamVisibilityTest`: extension and generic `...ForTest` functions are
  matched, and `@VisibleForTesting` must be on that declaration (in the
  annotation lines directly above, or inline). `AppLockController.contentReads`
  became `@VisibleForTesting internal val contentReadsForTest`.
- Python replication of every new regex over app/src/main and vault/src/main:
  no violations.

### U-03: primary contrast claim

Constrained to the surface roles and primaryContainer (computed: light
5.14:1, dark 5.25:1). Not claimed: light secondaryContainer 4.35:1, dark
tertiaryContainer 3.48:1, dark errorContainer 4.00:1, dark attentionContainer
4.49:1. `PaletteContrastTest.otherContainersAreUsedOnlyThroughTheTheme`
fails if app code outside `ui/theme` names those containers.
`everyBriefPairMeetsWcagAa` now checks onPrimary on the ink tokens that back
the primary role (`VermilionInk`, `DarkVermilionInk`). The VISUAL_IDENTITY
table row no longer says primary buttons are `#C8412B`.

### U-04: accessibility Cut

Kept and documented. Semantics can override the `CutText` action, but the
core field adds it only while text is selected, and an override would add a
permanent no-op Cut action that TalkBack announces even with no selection.
Nothing reaches the clipboard; the selected text is removed from the field.

### Optional: `endExternalActivity()` in `onResume`

Not done. The biometric and device-credential unlock (LockScreen) begins an
external trip, and on API 29 the credential screen can pause and resume the
activity during it. Ending the trip on resume could relock behind the user
mid-PIN, and that cannot be checked without a device. The ~700 ms residual
from Q-02 stands.

## Existing tests changed

| Test | Reason |
| --- | --- |
| `AppLockStateTest.anExternalActivityStartedByOpenLifeDoesNotRelock` | Removed. It wrote down the unbounded exemption (R1). The short-trip case is kept as `aShortExternalTripStartedByOpenLifeDoesNotRelock`. |
| `MultiWindowScrubTest`, `NavigationBackTest`, `IntakeAndListFlowTest`, `AppLockFlowTest` | The open-source extra now carries the nonce (R17). |
| `AppLockFlowTest`, `IntakeAndListFlowTest` | They import `currentStatusForTest`, which moved to androidTest (R20). |
| `AppLockStateTest.aDroppedPickerResultCannotLeaveTheLockDisabled` | Review Q-02: it asserted the old backstop (every 60 s absence locks while the flag stays set). It now asserts the stricter behaviour: after a dropped result, an ordinary 5 s background with timeout 0 locks. |
| `AppLockFlowTest.droppedPickerResultDoesNotLeaveTheLockDisabled` | Rewritten for Q-02 without `advanceClockForTest`: trip, return, then a second background must lock. |
| `AppLockFlowTest.deviceCredentialUnlockOnApi29KeepsThePendingShare` | Device safety: emulator-only and opt-in (`allowDevicePinChange=true`); the `devicePin` argument is gone. |
| `ContentTextFieldPolicyTest.theToolbarNeverOffersCopyOrCut` | Removed with the dead `NoClipboardWriteTextToolbar`. `theNewContextMenuDropsCopyAndCutComponents` became the stricter allow-list test. |
| `ContentTextFieldTest.correctionFieldOffersNoCopyOrCut` | Renamed and strengthened (Q-08): Paste positive control, accessibility CopyText, clipboard save/restore. |
| `SourceEgressBoundaryTest.everyContentTextFieldUsesTheSafeWrapper`, `ButtonShapeSourceTest`, `TestSeamVisibilityTest` | Strengthened only (Q-06/Q-07). |
| `NavigationBackTest.deleteFromViewerKeepsListOnTheBackStack`, `OcrPersistenceFlowTest` | Q-08 hygiene; same assertions. |
| `ContentTextFieldTest` | U round: restores the clipboard only when the initial read succeeded; never clears it. |
| `SecureWindowTest` | Added `everyActivityExcludesItsWindowFromAutofill` (U-01). |
| `SourceEgressBoundaryTest`, `ButtonShapeSourceTest`, `TestSeamVisibilityTest` | Strengthened only (U-02). |
| `PaletteContrastTest.everyBriefPairMeetsWcagAa` | U-03: onPrimary is checked on `VermilionInk`/`DarkVermilionInk` (the primary role) instead of the brand Vermilion tokens. Added `otherContainersAreUsedOnlyThroughTheTheme`. |
| `AppLockFlowTest` | Reads `contentReadsForTest` (renamed seam, U-02); same assertions. |

## Commands run (real output)

| Commit | Command | Result |
| --- | --- | --- |
| RED 8676b1e | `./gradlew :app:testDebugUnitTest :vault:testDebugUnitTest :app:compileDebugAndroidTestKotlin :vault:compileDebugAndroidTestKotlin --continue` | Both androidTest compiles succeeded. app: 114 tests, 26 failed. vault: 134 tests, 5 failed. Every failure was intended. |
| GREEN 42c1d55 | `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL. app 114/114, vault 134/134. |
| REFACTOR 0ca8a6e | the same command | BUILD SUCCESSFUL in 5m 19s. app 114/114, vault 134/134. |
| 0ca8a6e | `scripts/plan-check.sh` | steps=82 errors=0 |
| 42c1d55 tree | `bash scripts/tests/run.sh` | 47 passed, 0 failed |
| none | `connectedDebugAndroidTest` (any module) | **Not run**: no device, by owner instruction. |
| review round | Gradle (any task) | **Not run** locally (coordinator instruction: the host carries one build). Hosted CI verifies after push. |
| second review round | Gradle (any task) | **Not run** locally (same instruction). Hosted CI verifies after push. |
