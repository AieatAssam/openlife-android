# P1-21 verification: minSdk 26 (Android 8.0)

Date: 2026-11-27. Branch `plan/minsdk-26`. Decision: ADR-0019.
No emulator and no adb were used (owner instruction). No instrumented test was
run on any device.

## Automated results (host, this commit)

Command: `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`

- Result: `BUILD SUCCESSFUL in 4m 8s`, `136 actionable tasks: 47 executed, 5 from cache, 84 up-to-date`, `exit=0`.
- Lint: `app/build/reports/lint-results-debug.txt` reads `No issues found.` (the trial build had 5 errors).
- JUnit XML totals for app, vault and eval test tasks: `tests=506 skipped=0 failures=0 errors=0`.
- Targeted RED-to-GREEN: `AllowedAuthenticatorsTest` 2/2, `ManifestBoundaryTest` 7/7, `ApiLevelResourceTest` 1/1 (commit 0077c38).
- Merged release manifest (`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`): `<uses-permission android:name="android.permission.USE_FINGERPRINT" android:maxSdkVersion="27" />`; no `android.permission.INTERNET` line.

Command: `scripts/plan-check.sh` output: `steps=83 errors=0`.

Command: `bash scripts/tests/run.sh` output: `51 passed, 0 failed`.

Pre-existing configuration-cache warnings appeared (checkLicense tasks, DefaultProject); not introduced here.

## Device checklist (unverified: gap, pending physical-device run)

Device: owner's API 26 tablet. Install a debug build with `adb install` from the owner's machine. Record each item's result, date and build hash below. Until a row is filled in, the item is a gap.

| # | Check | Status |
| --- | --- | --- |
| 1 | App installs and launches on Android 8.0 (API 26) | unverified (gap — pending physical-device run) |
| 2 | App lock with fingerprint (BIOMETRIC_WEAK \| DEVICE_CREDENTIAL): prompt appears, fingerprint unlocks, cancel stays locked | unverified (gap — pending physical-device run) |
| 3 | App lock with PIN/pattern only (no fingerprint enrolled): device-credential prompt unlocks | unverified (gap — pending physical-device run) |
| 4 | Pending share survives a credential unlock (AppLockFlowTest A-12 behaviour on device) | unverified (gap — pending physical-device run) |
| 5 | Photo pick via the fallback picker (PickVisualMedia below API 33) opens and returns an image | unverified (gap — pending physical-device run) |
| 6 | Share intake from another app's share sheet, including a photo | unverified (gap — pending physical-device run) |
| 7 | OCR on a picked photo produces text (SampledBitmapDecoder and OcrBitmapPreparer paths) | unverified (gap — pending physical-device run) |
| 8 | About screen shows the signing-certificate fingerprint line (legacy `signatures` path) | unverified (gap — pending physical-device run) |
| 9 | About screen shows the wrapping-key line as "inside secure hardware" or software (no securityLevel on API 26-30) | unverified (gap — pending physical-device run) |
| 10 | Light navigation-bar icons correct in light theme (activity enableEdgeToEdge path; bytecode-checked only) | unverified (gap — pending physical-device run) |
| 11 | Dark theme and night navigation bar render without errors | unverified (gap — pending physical-device run) |
| 12 | Backup and restore on API 26 (backup_rules.xml; data_extraction_rules.xml is Android 12+ only) | unverified (gap — pending physical-device run) |

## Instrumented coverage gaps

- `SecureWindowUiTest.everyContentWindowAndDialogIsSecure` is skipped on API 26-28 (WindowInspector is API 29+). FLAG_SECURE on those levels has no automated check.
- `AppLockFlowTest` A-12 runs only on API 26-29 and skips elsewhere; it has not been run on any device.
- Nothing in CI runs API 26: the CI matrix has API 29 and API 36 legs only. Emulators are excluded by the owner.

## Source review of API-29-sensitive code (file:line)

- `app/src/main/java/org/openlife/app/settings/AppAboutPort.kt`: SDK_INT >= P branch uses `GET_SIGNING_CERTIFICATES` and `signingInfo`; below P it reads the legacy `signatures` with a targeted `@Suppress("DEPRECATION")`. Both branches yield the signing certificates.
- `app/src/main/java/org/openlife/app/lock/BiometricPromptAuthenticator.kt`: `allowedAuthenticatorsFor(sdkInt)`; STRONG from API 30, WEAK below. Covered by `AllowedAuthenticatorsTest`.
- `app/src/main/AndroidManifest.xml`: `USE_FINGERPRINT` with `maxSdkVersion="27"`.
- `app/src/main/res/values-v27/themes.xml` and `values-night-v27/themes.xml`: `windowLightNavigationBar`. Below 27 the activity's `enableEdgeToEdge()` sets the appearance.
- `vault/src/main/java/org/openlife/vault/crypto/KeystoreWrapper.kt`: options used are API 23+; StrongBox and unlocked-device options are not set; `securityLevel` read only at API 31+ (line ~77).
- `app/src/main/java/org/openlife/app/settings/VaultSecurityInfo.kt`: comments updated to API 26-30.
- `app/src/main/java/org/openlife/app/intake/IntakeActivity.kt` (around line 183): `TIRAMISU` guard on the parcelable extra helper. Fine at 26.
- `app/src/main/java/org/openlife/app/MainActivity.kt` (around line 90): `PickVisualMedia`. Below API 33 the AndroidX fallback is used. Not device-verified (checklist item 5).
- `MainActivity.kt` (around line 82) and `IntakeActivity.kt` (around line 87): `enableEdgeToEdge()`. Works on 26 through the activity library. Not device-verified (item 10).
- `app/src/androidTest/java/org/openlife/app/ui/SecureWindowUiTest.kt`: `WindowInspector` is API 29+; skipped below Q (see gaps).
- Not API-29-sensitive: `ImageDecoder` (not used); `BitmapFactory` in `SampledBitmapDecoder.kt` and `OcrBitmapPreparer.kt` (fine); `java.time` in vault facts and Type.kt fonts: not checked in this step; lint reported no API errors for them at minSdk 26.

## Device run 2026-11-27, Huawei AGS2-W09, API 26

Device: Huawei AGS2-W09, Android 8.0.0, `ro.build.version.sdk=26`, adb serial U8BBB20806201256. Code: commit `a212431` (test-only fixes on top of the minSdk change). Gradle connected runs; logs and XML under `scratchpad/device-logs/` (`vault-run2.log`, `vault-results-run2/`, `app-run2.log`, `app-results-run2/`). No emulator was used. Only `org.openlife` and `org.openlife.test` were installed by Gradle.

Device-free gate on `a212431` (`detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`): `GATE_EXIT=0`. Lint with `checkTestSources` enabled (temporary probe) reported NewApi only in host `src/test` files (vault `GoldenEnvelopeFixtureTest`, app `ApiLevelResourceTest` and others); the androidTest sites guarded in this step were not flagged. The probe is not committed.

### Vault module, run 2 (`:vault:connectedDebugAndroidTest --continue`, exit 1)

- XML: `tests=171 failures=4 errors=0 skipped=0`.
- Failures:
  1. `ocr.OcrEngineMeasurementTest.measureEnginesOnSyntheticFixtures`: empty failure element. Opt-in class, argument not set (see harness note).
  2. `ocr.eval.OcrEvaluationTest.evaluateRegisteredEnginesOverTheFixtureSet`: empty failure element. Opt-in class, argument not set.
  3. `repository.ImportRepositoryTest.slowProviderReadDoesNotBlockViewerReadsOrDeletion`: `java.lang.AssertionError: deletion and a viewer read must finish within 1 s while the provider stalls`, at `org.junit.Assert.assertTrue(Assert.java:42)`. Observed, not investigated in this step.
  4. `repository.RecoveryTimingTest.recoveryTimeWithFiftySources`: empty failure element. Opt-in class, argument not set.
- C1, C2 and C3 (the `OcrDatabaseTest`, `SourceDatabaseTest` and `VaultBootstrapperTest` failures from run 1) do not appear in run 2.

Harness note. With the argument unset, the runner logs `ignored: recoveryTimeWithFiftySources` (logcat, `TestRunner`, `run finished: 0 tests, 0 failed, 1 ignored`), yet the Gradle XML records a `<failure>` with no message. The three empty failures therefore come from how the ignored opt-in tests are reported, not from test code. This is observed for `RecoveryTimingTest`; the same mechanism and the same empty message apply to the other two classes (inference, not separately logged).

### Opt-in measurements (argument `true`; no thresholds relaxed)

- C4 `OcrEngineMeasurementTest` (`-Pp203Measure=true`): passes (XML `tests=1 failures=0`). Per-fixture runs in ms, with peak PSS:
  - tesseract-eng-fast, receipt-900x360: 896, 905, 949; peak PSS 64182 kB.
  - tesseract-eng-fast, screenshot-1080x2400: 2874, 2851, 2932; peak PSS 75849 kB.
  - tesseract-eng-fast, dense-1200x1600: 4061, 4073, 4033; peak PSS 67535 kB.
  - mlkit-latin, receipt-900x360: 324, 337, 312; peak PSS 60114 kB.
  - mlkit-latin, screenshot-1080x2400: 1152, 927, 887; peak PSS 91110 kB.
  - mlkit-latin, dense-1200x1600: 1205, 1161, 1109; peak PSS 99006 kB.
- C5 `OcrEvaluationTest` (`-Pp204Eval=true -Pp204Commit=a2124316894bfea2d53e3a19082950366493b339`, the HEAD commit recorded in the results JSON): the JUnit test passes (XML `tests=1 failures=0`). The eval logged one engine, `mlkit-latin`, with `gate=FAIL`: `abstention_correct_rate_non_latin FAIL observed=0.0`. The other checks logged PASS (for example `cer_mean_receipt observed=0.02328556294786782`, `line_match_rate observed=0.9980806142034548`, `duration_p95_ms observed=759.0`). No Tesseract result appears in this run. The first attempt without `p204Commit` failed with the message that the argument is required.
- C6 `RecoveryTimingTest` (`-Pp114Timing=true`): passes (XML `tests=1 failures=0`). The test asserts no threshold, only logs: `sources=50 blobBytes=44788827 recoverMs=[91, 73, 54, 51, 39] medianMs=54`.

### App module, run 2 (`:app:connectedDebugAndroidTest --continue`, exit 1, 25m45s)

- XML: `tests=146 failures=113 errors=0 skipped=0`. Assumption violations are recorded as `<failure>` elements, so they are counted in the 113.
- Failures grouped by first exception line:
  - 76: `IllegalStateException: No compose hierarchies found in the app` (the activity did not reach `setContent`). Example: `SettingsScreenTest.resetVaultRequiresTypedConfirmation` at `SettingsScreenTest.kt:46`. Logcat for `SettingsScreenTest` shows no `FATAL EXCEPTION`; the test activity was launched, then `appSwitch from org.openlife.test to org.openlife`. Root cause not established. Related failures show `Activity never becomes requested state "[RESUMED]" (last lifecycle transition = "STOPPED")` (`FirstRunFlowTest.kt:45`).
  - 4: `IntakeActivityTest` `status never contained 'Prepared'` (`IntakeActivityTest.kt:216`) (plus `status never reached Prepared; last=Preparing`).
  - 4: `InternalLinkTest` `list not shown` (`InternalLinkTest.kt:77`).
  - 4: `MultiWindowScrubTest` `viewer not shown` (`MultiWindowScrubTest.kt:105`).
  - 3: `IntakeAndListFlowTest` bare `AssertionError` (`IntakeAndListFlowTest.kt:392`).
  - 2: `PhotoPickerRouteTest` `preview never appeared` (`PhotoPickerRouteTest.kt:149`).
  - 2 each: `AppLockFlowTest` `lock screen not shown` (`AppLockFlowTest.kt:82`), `NullPointerException` (`AppLockFlowTest.kt:134`); `AboutScreenTest` `ComposeTimeoutException: Condition still not satisfied after 10000 ms` (`AboutScreenTest.kt:121`).
  - 2: `FirstRunFlowTest` activity lifecycle not RESUMED (above).
  - 1 each: `PhotoPickerRouteTest` picked image never reached preview, unsupported format not explained; `AppLockFlowTest` no cannot-verify explanation, `ScriptedAuthenticator.awaitRequest` `no authentication was requested`, and an `AssumptionViolatedException` for device PIN opt-in (`AppLockFlowTest.kt:222`); `IntakeAndListFlowTest` viewer did not show saved image; `NavigationBackTest` viewer did not load image; `ViewerByIdRealStoreTest` real store did not show source; `IntakeViewModel` `CreationExtras must have a value by SAVED_STATE_REGISTRY_OWNER_KEY` (`IntakeViewModel.kt:514`).
  - Assumption-as-failure, goldens: `BrandScreenshotInstrumentedTest` (3) `no committed ... goldens for render profile api26-AGS2-W09EEA-320dpi`.
- `SecureWindowUiTest` does not appear in the app XML at all in this run, so its outcome is unknown. `SecureWindowTest` (a different class) passes.
- Behavioural failures other than the API-level StrictMode and `NoSuchMethodError` failures from run 1 were not changed in this step.

### Unfinished

- Root cause of the 76 "No compose hierarchy" failures is not established. Next step: a logcat capture for one `ComponentActivity`-based class at the moment the activity is expected to resume.
- `ImportRepositoryTest.slowProviderReadDoesNotBlockViewerReadsOrDeletion` (1 s bound) fails on this device; not investigated.
- Eval gate failure for `mlkit-latin` (`abstention_correct_rate_non_latin`) is recorded, not investigated. No Tesseract eval result in this run.
- Lint `NewApi` is not enforced on androidTest sources: enabling `checkTestSources` produced 46 app and 15 vault findings, all in host `src/test` files. The one androidTest finding (`UseSdkSuppress` on `SecureWindowUiTest`) was fixed with `@SdkSuppress`.
- `SecureWindowUiTest`: outcome unknown (see above).

## Device run 3-5, 2026-11-27, Huawei AGS2-W09, API 26 (`plan/minsdk-26`)

Device evidence for these runs is in the scratchpad, not the repository:
`device-logs/app-results-run3/`, `device-logs/run4-<Class>.log|.xml`,
`device-logs/app-run5.log` and `device-logs/app-results-run5/`.

### Run 3 (baseline)

- XML: `tests=146 failures=19 errors=0 skipped=0`: 15 real failures and 4 opt-in or assumption entries.

### Run 4 (per-class re-runs, one Gradle invocation each)

Classification uses four categories: (a) product defect, (b) API-26 or tablet behaviour, (c) test assumes phone or emulator size, (d) flaky or harness timing. A separate "test defect" category covers a fault in the test code itself.

Fixed in this step (each change is test-only; no production code changed):

- `SavedStateTest.viewerWithOcrPanelSavesNoSpanTextOrBytes`: test defect. The fixture span had `evidenceRegion = null`, and `EvidenceSelection.selectableSpans` drops such spans, so the viewer never showed its selection announcement and the wait timed out after 30 s. The class also used `createEmptyComposeRule()`; it now uses `createAndroidComposeRule<ComponentActivity>()`. Passes alone (`run4-SavedStateTest.xml`, `tests=1 failures=0`).
- `savedBundleHoldsNoContent`, moved to the new `SearchQuerySavedStateTest` (no compose rule in that class): (d), harness. With the compose rule declared in the same class, the real list stayed on its "Loading..." placeholder although `OpenLifeApp` had emitted `Loaded` (logcat: `vm state=Loaded` at +1.7 s; pane last composed with `Loading`). Without the rule, the pane recomposed with `Loaded` at +1.7 s and the test passed in 6.9 s. The mechanism was not identified. Product path checked by manual launch on the tablet (no instrumentation): the list left "Loading..." and showed "Nothing kept yet". The test now waits for the loading text to disappear before tapping search. Passes alone (`run4-SearchQuerySavedStateTest.xml`, `tests=1 failures=0`).
- `fieldIsEmptyAfterTheActivityStops`, moved from `SearchScreenTest` to the new `SearchFieldResetTest`: (d) and a test assumption. `MainActivity.onStop` clears the search query (ADR-0010), but the search screen can stay open. The test assumed the search button returned. The reopen step now accepts either the open field or the search button, and the assertion that the query text is absent is unchanged. Passes alone (`run4-SearchFieldResetTest.xml`, `tests=1 failures=0`). `SearchScreenTest` passes alone, 2 of 2.
- `IntakeScreenFlowTest.saveThenHomeEndsReadyOrCleanlyCancelledNeverUnknown`: test defect. `ViewModelProvider.create` was called without `CreationExtras`, so `createSavedStateHandle()` threw `IllegalArgumentException: CreationExtras must have a value by VIEW_MODEL_STORE_OWNER_KEY`. The test now supplies a `ComponentActivity` (from `ActivityScenario`) as both owners, as `IntakeActivity` does through `viewModels()`. Assertions unchanged. Class passes alone, 3 of 3 (`run4-IntakeScreenFlowTest.xml`).
- `AdaptiveLayoutTest.expandedBackClearsSelectionBeforeLeaving`: test defect. The assertion read `composeRule.activity` after the second Back had destroyed it (`NullPointerException: Cannot run onActivity since Activity has been destroyed already`). The activity is now captured before that Back; the same `isFinishing` assertion is kept. Passes in the class run (`run4-AdaptiveLayoutTest-fix.xml`).

Not fixed; open, with the evidence observed:

- `InternalLinkTest.openExistingGoesThroughTheInternalEntryWithNonce` (line 57): `ActivityScenario.close` cannot move `MainActivity` to DESTROYED; it stays PAUSED after the internal link opens the viewer in another task. Not classified.
- `AccessibilitySemanticsTest.stampBadgeLabelIsAnnouncedOnce` (line 398): the merged-tree check passes (one announcing node). The unmerged-tree check counts 2 nodes (the labelled container and its child text). The assertion was not loosened; whether the unmerged check is intended needs an owner decision.
- `AdaptiveLayoutTest.selectionSurvivesCompactToExpandedAndBack` (line 97): after a resize to 1280x800 dp, `Details` is not displayed. Not classified.
- `EdgeToEdgeUiTest.contentClearsSystemBarsWithLargeInsets` (line 60): the title's top is above the injected inset. Not classified. The test also uses `createEmptyComposeRule()` with `MainActivity`, the pattern of the list stall above; this was not tested.
- `EvidenceNavigationTest` (3): `doubleTapZoomsImageToThreeTimesItsFitWidth` (zoomed width expected 10368, was 3456), `doubleTapAtThreeTimesZoomsOutEvenWhenTheFirstTapPans` (expected 1152, was 3456), `rejectedSpanRegionCannotBeSelectedAsEvidence` (node count). Not classified; double-tap zoom did not apply on the device.
- `LargeFontUiTest.noControlIsClippedAtFontScaleTwoOn320x640` (line 91): the list's text overflows its layout at font scale 2. Possibly (a), a product layout defect; not changed.
- `ViewerScreenC1Test.staleExtractionIsGreyedAndExplained` (line 367): `performScrollTo()` cannot find the node. Not classified.
- `ButtonShapeTest.buttonsUseTheEightDpShape` (line 59): the corner pixel is outside the shape (colour `-5753824`, expected `-16711936`). Possibly (b), API-26 rendering; not classified.

### Run 5 (`./gradlew :app:connectedDebugAndroidTest --continue`, exit 1)

- XML: `tests=146 failures=14 errors=0 skipped=0`. Of the 14, 4 are opt-in or assumption entries, left unchanged: `AppLockFlowTest.deviceCredentialUnlockBelowApi30KeepsThePendingShare` (skipped unless `allowDevicePinChange` is passed); `BrandScreenshotInstrumentedTest` `listDetailExpandedMatchesGolden`, `deleteDialogMatchesGolden`, `listViewerFirstRunMatchGoldensLightAndDark` (no committed goldens for render profile `api26-AGS2-W09EEA-320dpi`).
- 10 real failures remain, the open items listed above: InternalLinkTest (1), AccessibilitySemanticsTest (1), AdaptiveLayoutTest (1), EdgeToEdgeUiTest (1), EvidenceNavigationTest (3), LargeFontUiTest (1), ViewerScreenC1Test (1), ButtonShapeTest (1).
- The five fixed items above all pass in run 5 (`SavedStateTest`, `SearchQuerySavedStateTest`, `SearchFieldResetTest`, `SearchScreenTest`, `IntakeScreenFlowTest`; `AdaptiveLayoutTest` now fails only on line 97).

### Device-free gate

- `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`: exit 0.

### Unverified

- The 10 open real failures have not been fixed or classified beyond the notes above. Each remains a device-verification gap.
