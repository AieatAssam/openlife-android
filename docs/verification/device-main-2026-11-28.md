# Device verification: full instrumented suites on main, 2026-11-28

Commit under test: `9859ba6` (`Merge pull request #43 from AieatAssam/fix/facts-eval-findings`), the tip of
`origin/main`. Worktree branch `verify/device-main-2026-11-28`, created from `origin/main`. No production code
or tests were changed. No emulator was used. Nothing was pushed.

## Device

- Model: Huawei AGS2-W09, Android 8.0.0, `ro.build.version.sdk=26`, adb serial `U8BBB20806201256`.
- The tablet has no lock. The screen was kept awake with `stay_on_while_plugged_in 3` and `KEYCODE_WAKEUP`
  and `wm dismiss-keyguard` before runs. It was set back to `0` after the runs. No credentials were changed.
- Only `org.openlife` and `org.openlife.test` were installed or removed by Gradle.

## Commands

- Vault: `./gradlew :vault:connectedDebugAndroidTest --continue`, `BUILD SUCCESSFUL in 5m 44s`.
  Vault failures are read from the XML, not from the Gradle exit state.
- App, one Gradle call per test class: `./gradlew :app:connectedDebugAndroidTest --continue
  -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`.
  Earlier app runs used `-Pandroid.testInstrumentationRunnerArguments.package=<pkg>`, which ran only the
  classes directly in that exact package (see Harness notes).
- XML and logs (not in the repository): `scratchpad/device-logs/2026-11-28-main/vault/`,
  `scratchpad/device-logs/2026-11-28-main/app/<run>/`, Gradle logs `scratchpad/device-logs/2026-11-28-main/*-gradle.log`.

## Totals

| Module | Tests | Failures | Errors | Skipped | Notes |
| --- | --- | --- | --- | --- | --- |
| `:vault` | 195 | 4 | 0 | 0 | All 4 are the opt-in measurement classes (see table). |
| `:app` | 149 | 7 | 0 | 0 | All 7 are on the known list. `SecureWindowUiTest` reports 0 testcases (API 29+ only, not a failure). |

## Failures

| Module | Test | Message (first line) | Known / new | Reproduces |
| --- | --- | --- | --- | --- |
| vault | `ocr.OcrEngineMeasurementTest.measureEnginesOnSyntheticFixtures` | empty failure element (opt-in not set) | known (opt-in) | yes |
| vault | `ocr.eval.OcrEvaluationTest.evaluateRegisteredEnginesOverTheFixtureSet` | empty failure element (opt-in not set) | known (opt-in) | yes |
| vault | `repository.RecoveryTimingTest.recoveryTimeWithFiftySources` | empty failure element (opt-in not set) | known (opt-in) | yes |
| vault | `facts.eval.FactsImageEval.measureBothEnginesOverTheFactsImages` | empty failure element (opt-in not set) | known (opt-in) | yes |
| app | `a11y.ScreenSemanticsTest.everyScreenPassesAccessibilityChecks` | `accessibility failures:` (details in the XML body) | known | yes |
| app | `ui.brand.BrandScreenshotInstrumentedTest.listDetailExpandedMatchesGolden` | `AssumptionViolatedException: no committed list-detail goldens for render profile api26-AGS2-W09EEA-320dpi` | known | yes |
| app | `ui.brand.BrandScreenshotInstrumentedTest.deleteDialogMatchesGolden` | `no committed delete-dialog goldens for render profile api26-AGS2-W09EEA-320dpi` | known | yes |
| app | `ui.brand.BrandScreenshotInstrumentedTest.listViewerFirstRunMatchGoldensLightAndDark` | `no committed goldens for render profile api26-AGS2-W09EEA-320dpi` | known | yes |
| app | `ui.EdgeToEdgeUiTest.contentClearsSystemBarsWithLargeInsets` | `AssertionError: the title is under the status bar` | known | yes |
| app | `ui.LargeFontUiTest.noControlIsClippedAtFontScaleTwoOn320x640` | `list: a control is clipped horizontally: 'null' 620 x 112 Rect.fromLTRB(0.0, 1136.0, 608.0, 1248.0)` | known | yes |
| app | `lock.AppLockFlowTest.deviceCredentialUnlockBelowApi30KeepsThePendingShare` | `AssumptionViolatedException: skipped: sets a device PIN; pass -e allowDevicePinChange true to opt in` | known (not set, by design) | yes |

Vault `ImportRepositoryTest.slowProviderReadDoesNotBlockViewerReadsOrDeletion` (failed on the earlier P1-21 run)
passed in this run.

### New failure from the package run, not reproduced

- `ui.AccessibilitySemanticsTest.primaryActionStaysAboveBottomInsetInSmallWindow` failed once in the
  `package=org.openlife.app.ui` run, with an empty message. Source: `app/src/androidTest/java/org/openlife/app/ui/AccessibilitySemanticsTest.kt:113`.
  The class was re-run alone (`rerun-AccessibilitySemanticsTest`, 12 tests, 0 failures). The test passed.
  Status: did not reproduce on a single re-run. The earlier package run recorded 8 testcases for this class,
  and the class run recorded 12, so the first run is not fully explained.

### Earlier open failures that pass in this run

From the P1-21 record, runs 4-5: `InternalLinkTest.openExistingGoesThroughTheInternalEntryWithNonce`,
`AdaptiveLayoutTest.selectionSurvivesCompactToExpandedAndBack`, `EvidenceNavigationTest` (3),
`ViewerScreenC1Test.staleExtractionIsGreyedAndExplained`, `ButtonShapeTest.buttonsUseTheEightDpShape`.
All of these pass here (`InternalLinkTest` 2/2, `AdaptiveLayoutTest` 5/5, `EvidenceNavigationTest` 8/8,
`ViewerScreenC1Test` 10/10, `ButtonShapeTest` 1/1). The earlier failures therefore did not reproduce on this run.

## Harness notes (this run)

- `-Pandroid.testInstrumentationRunnerArguments.class=a,b,c` ran only the first class. Each class was then run
  alone. The batch XML directories `app/b1..b4` contain only the first class of each batch and are used only
  for those classes.
- `package=org.openlife.app.ui` ran only `AccessibilitySemanticsTest`. The package filter is therefore not a
  reliable way to run every class in a package. The `app/ui` directory is kept for the record and excluded from the totals.
- The Gradle gate before each run checks `pgrep -f 'java.*[G]radleWrapperMain'` and
  `pgrep -f 'java.*[c]onnectedDebugAndroidTest'`. The `java.*` prefix was added because a non-Java bash wrapper
  matched the bare `connectedDebugAndroidTest` pattern and blocked runs. The wrapper was not touched.
- One app run (`app-intake`) was started without the gate, after a PID printed by the pre-check that had already
  exited before it could be identified. The intake result (15 tests, 0 failures) is recorded, but the run may
  have overlapped another agent's device use.
- Other agents used the host during this session, so some runs waited.

## Coverage

- Every test class with `@Test` in `app/src/androidTest` ran at least once, except `SecureWindowUiTest` (0 testcases).
- Passing app classes in this run (all tests passed): `IntakeActivityTest`, `InternalLinkTest`, `PhotoPickerRouteTest`,
  `AboutScreenTest`, `SettingsRotationTest`, `SettingsScreenTest`, `BackupRulesTest`, `AccessibilitySemanticsTest`,
  `AdaptiveLayoutTest`, `AutofillExclusionTest`, `ButtonShapeTest`, `ContentTextFieldTest`, `CoreFlowUxTest`,
  `DeletionRetryFlowTest`, `EvidenceNavigationTest`, `FirstRunFlowTest`, `IntakeAndListFlowTest`,
  `IntakeScreenFlowTest`, `IntakeScreenTest`, `LargeImportTest`, `ListMemoryBudgetTest`, `MainThreadPolicyTest`,
  `MultiWindowScrubTest`, `NavigationBackTest`, `OcrPersistenceFlowTest`, `RtlUiTest`, `SampledBitmapDecoderTest`,
  `SavedStateTest`, `SearchQuerySavedStateTest`, `SearchFieldResetTest`, `SearchScreenTest`, `SecureWindowTest`,
  `SourceListScreenTest`, `VaultUnavailableFlowTest`, `ViewerByIdRealStoreTest`, `ViewerByIdTest`,
  `ViewerScreenC1Test`, `ViewerScreenTest`, plus `ScreenSemanticsTest` (known failure, not counted as passing),
  `BrandScreenshotInstrumentedTest` and `LargeFontUiTest` and `EdgeToEdgeUiTest` and `AppLockFlowTest` (known failures).
- Vault: all test classes ran; the only failures are the 4 opt-in classes above.

## Plan steps

No step is claimed as fully device-verified by this record. A step is covered only when every instrumented class it
owns passed, and that was not enumerated per step. The grep-based candidates (`docs/verification` and `plan/steps`
mentions) are not authoritative. Classes whose step is not obvious are unmapped.

- Passing classes that are clearly tied to P1-21 (the API 26 gap list in `docs/verification/P1-21-min-sdk-26.md`):
  `AppLockFlowTest` (except the device-PIN opt-in), `SecureWindowTest`, `SecureWindowUiTest` (0 testcases, unverified on API 26),
  `EdgeToEdgeUiTest` (known failure), `ScreenSemanticsTest` (known failure), `BrandScreenshotInstrumentedTest` (no goldens).
- Unmapped: every other passing class listed above.

## Unverified

- Device checklist items 1-12 of P1-21 are not filled by this record. This run exercises instrumented tests only.
- `SecureWindowUiTest` on API 26 is skipped (`0` testcases): FLAG_SECURE on API 26 is unverified.
- Known failures: `ScreenSemanticsTest`, `EdgeToEdgeUiTest`, `LargeFontUiTest`, the 3 `BrandScreenshotInstrumentedTest` golden tests, and `AppLockFlowTest` (device-PIN opt-in).
