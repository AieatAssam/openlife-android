# P1-20 Navigation and state architecture rebuild

Branch `plan/P1-20-navigation-state`, from `main` at cc7b768; `origin/main`
(82a4188, P2-04) merged before the final gate. Dates 2026-11-25 to
2026-11-26. Addresses F-60 and F-64; their closure is pending the device
run below.

## Device policy for this run

Owner instruction: no emulator, no `connected*AndroidTest` task and no `adb`
(the attached tablet belongs to another agent). None was started or run.
Robolectric is not available and was not added. Navigation and state logic
is proven with pure JVM holders. Every instrumented test below was
**compiled** (`:app:compileDebugAndroidTestKotlin
:vault:compileDebugAndroidTestKotlin`) and **not run**. Each one's status is
**implemented; unverified (gap — pending physical-device run)**. None of
them is evidence that anything is fixed.

## Architecture

- **R1, container.** `OpenLifeApp.container` is an `AppContainer`
  (`OpenLifeAppContainer`): `SourceStore`, the one `OcrPresenter`, the
  search backend, `ContentScrubber`, `InternalLinks` and `SettingsPort`.
  `OpenLifeNavHost(container, navController)` is the whole signature (the
  window width is read inside it with `BoxWithConstraints`). Each
  destination builds its ViewModel with `viewModel(entry, factory)`, so it
  is scoped to its back-stack entry; `ViewerViewModel` reads its route with
  `SavedStateHandle.toRoute<Routes.ListDetail>()`. The activity-scoped
  `OcrViewModel` and P2-08's `SearchHostState` are gone; `MainActivity`
  holds no ViewModel.
- **R2, by id.** `SourceDao.observeVisibleSource(id)` →
  `SourceViewRepository.observeVisibleSource(id)` → `SourceStore.observeSource`
  → `ViewerSession`. The list is not consulted. `Missing` shows
  "Content unavailable" and sets a one-shot exit (`consumeExit`), which the
  destination honours only while its entry is current. `SourceScreens.find`
  is deleted.
- **R3, list-detail.** `Routes.ListDetail(sourceId, spanId?)` is the single
  detail destination. `ListDetailNavigation` (pure) decides the panes from
  (selection, width) and the back-stack action from (top destination,
  width). A resize recomposes panes only.
- **R4, scrub.** `ContentScrubber.scrub(hidden)`: `onStop` scrubs with
  `hidden = true` (the viewer releases its bitmap and OCR text and reloads
  on return); memory trim and vault reset scrub with `hidden = false`. The
  destination is kept. Bitmaps are recycled synchronously on drop, guarded
  by an epoch; a composable still drawing the old bitmap is the same device
  risk recorded for F-28.
- **R5, internal entry.** `InternalLinkActivity` (not exported, no filter,
  `Theme.NoDisplay`, FLAG_SECURE) accepts kind + id + single-use nonce,
  queues the id in `InternalLinks`, and starts MainActivity with no extras.
  MainActivity takes the pending link only after the lock gate.
- **R9, viewer state.** `ViewerViewModel`/`ViewerSession` own the selected
  span, zoom and pan, saved as a UUID string and floats in the
  `SavedStateHandle`; `ViewerScreen`'s public signature is unchanged.

## JVM evidence

Full gate, 2026-11-26, after merging `origin/main` (82a4188):
`./gradlew detekt lint :app:test :vault:test :eval:test assembleDebug
:vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` —
BUILD SUCCESSFUL in 7m 28s.

| Suite | Before (cc7b768) | After | Failures |
| --- | --- | --- | --- |
| `:app` JVM (debug unit) | 146 (derived from the diff, 175 − 31 + 2; not run at cc7b768) | 175 (+31 new, −2 in the deleted unit `NavigationBackTest`) | 0 |
| `:vault` JVM | 191 (run at e69ba0b, before the merge) | 193 (+2 from P2-04 via the merge) | 0 |
| `:eval` JVM (P2-04) | — | 26 | 0 |

`scripts/plan-check.sh`: `steps=82 errors=0`. `bash scripts/tests/run.sh`:
51 passed, 0 failed.

New JVM proof: `ContentScrubberTest`, `ViewerSessionTest` (by-id loading,
missing → one exit in both orders, scrub on hide/trim, late results
released, OCR cleared, span/zoom/pan saved as ids and floats only),
`ListDetailNavigationTest` (panes and back-stack actions for every width),
`InternalLinksTest` (kind, id and single-use nonce), `RouteSavedStateTest`,
`ViewModelScopingTest`, `ManifestBoundaryTest.internalLinkEntryIsNotExported`.

## Recorded mutations

- RED commit 9c8293f: the RED run of the new and touched classes executed
  36 tests (31 new `@Test`s plus existing ones in touched classes), 29 failing — `NotImplementedError`
  from the stubs (`ContentScrubber` 2, `ViewerSession` 13,
  `ListDetailNavigation` 8, `InternalLinks` 3), `ViewModelScopingTest` 2 on
  their assertions (an activity `getViewModel` getter; a ViewModel built
  from `OpenLifeApp`) and `ManifestBoundaryTest.internalLinkEntryIsNotExported`
  ("the internal link entry is declared").
- `RouteSavedStateTest` passed at RED (it guards existing routes), so its RED
  is a mutation: adding `val label: String = ""` to `Routes.ListDetail` fails
  `everyRouteArgumentIsAnIdString` (2 tests, 1 failed, `AssertionError` at
  line 24); reverted.
- Device mutations for the instrumented replacements: not run (see below).

## Tests changed

Deleted: unit `NavigationBackTest` (asserted the old viewer-redirect design
by source grep); replaced by `ListDetailNavigationTest` (JVM) and the
instrumented `NavigationBackTest.backFromEveryDestination`.

Changed, JVM:
- `SecureWindowTest`: the activity list now has three entries
  (`InternalLinkActivity` added).
- `ManifestBoundaryTest`: adds `internalLinkEntryIsNotExported`; still
  exactly 2 exported activities.
- `SaveCommitTest`, `UserContentKindWriteTest` (vault): their fake DAOs
  implement the new `observeVisibleSource` as `unused()`.
- `BoundedStreamReaderTest` (vault): a comment justifying its sleep.
- `ViewerSessionTest` (added at RED): its `SavedValues` fake gained
  `contains`, which GREEN added to the interface; one style fix.

Changed, instrumented (compile only):
- `MultiWindowScrubTest`: the documented R4 change — after hiding, the
  viewer reloads instead of navigating away; adds
  `backgroundingClearsContentButKeepsTheDestination`; polling sleeps gone.
- `NavigationBackTest`: opens through the internal-link queue, not the
  launcher extra; the delete test no longer uses the removed
  `beforeDeleteResultForTest` seam (the single exit is proven on the JVM in
  both orders); adds `backFromEveryDestination`; the destroy wait uses a
  lifecycle callback instead of a sleep loop.
- `AdaptiveLayoutTest`, `SearchScreenTest`: hosted on the real graph over
  `FakeAppContainer` instead of the 17-parameter `OpenLifeNavHost`;
  `SearchScreenTest` now types the query.
- `AppLockFlowTest`, `IntakeAndListFlowTest`: open sources via
  `requestOpenSource`; `SourceListViewModel(application.container)`.
- `DeletionRetryFlowTest`, `SourceListScreenTest`, `ViewerScreenC1Test`,
  `MainThreadPolicyTest`, `OcrPersistenceFlowTest`: construction only
  (`application.container`, `appOcrPresenter`). R9 asked for
  `ViewerScreenC1Test` unchanged; its assertions are, its setup line is not.
- `BrandScreenshotInstrumentedTest`: adds `listDetailExpandedMatchesGolden`.
- `SourceViewRepositoryTest` (vault): adds
  `observeVisibleSourceFollowsOneSourceByIdUntilItIsGone`.
- `IntakeActivityTest`, `IntakeScreenFlowTest`, `ScriptedAuthenticator`,
  `OcrEngineMeasurementTest`, `OcrRepositoryTest`: sleep justifications only.

`EvidenceNavigationTest` is unchanged.

## Instrumented tests awaiting the tablet

| Test | Command (run on the tablet when it is free) |
| --- | --- |
| `ViewerByIdTest` (3) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.ViewerByIdTest` |
| `AdaptiveLayoutTest` (2 new, 3 migrated) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.AdaptiveLayoutTest` |
| `MultiWindowScrubTest` (1 new, R4 change) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.MultiWindowScrubTest` |
| `SavedStateTest.savedBundleHoldsNoContent` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.SavedStateTest` |
| `InternalLinkTest` (2) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.intake.InternalLinkTest` |
| `SecureWindowUiTest.everyContentWindowAndDialogIsSecure` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.SecureWindowUiTest` |
| `LargeFontUiTest.noControlIsClippedAtFontScaleTwoOn320x640` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.LargeFontUiTest` |
| `RtlUiTest.listAndViewerMirrorInRtl` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.RtlUiTest` |
| `NavigationBackTest` (4, incl. `backFromEveryDestination`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.NavigationBackTest` |
| `EdgeToEdgeUiTest.contentClearsSystemBarsWithLargeInsets` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.EdgeToEdgeUiTest` |
| `BrandScreenshotInstrumentedTest.listDetailExpandedMatchesGolden` (no PNG committed: first generate on the CI profile with `-Pandroid.testInstrumentationRunnerArguments.generateGoldens=true`) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.brand.BrandScreenshotInstrumentedTest#listDetailExpandedMatchesGolden` |
| `SourceViewRepositoryTest.observeVisibleSourceFollowsOneSourceByIdUntilItIsGone` (vault) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.SourceViewRepositoryTest` |
| Migrated, behaviour unchanged: `SearchScreenTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.search.SearchScreenTest` |
| Migrated, behaviour unchanged: `AppLockFlowTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.lock.AppLockFlowTest` |
| Migrated, behaviour unchanged: `IntakeAndListFlowTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.IntakeAndListFlowTest` |
| Migrated, behaviour unchanged: `DeletionRetryFlowTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.DeletionRetryFlowTest` |
| Migrated, behaviour unchanged: `SourceListScreenTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.SourceListScreenTest` |
| Migrated, behaviour unchanged: `ViewerScreenC1Test` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.ViewerScreenC1Test` |
| Migrated, behaviour unchanged: `EvidenceNavigationTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.EvidenceNavigationTest` |
| Migrated, behaviour unchanged: `MainThreadPolicyTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.MainThreadPolicyTest` |
| Migrated, behaviour unchanged: `OcrPersistenceFlowTest` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.OcrPersistenceFlowTest` |

The device mutation-RED for each replacement (R6) is also a gap: on the
tablet, before deleting a grep test, apply its mutation (drop
`applySecureWindow()` from InternalLinkActivity; set `maxLines = 1` on the
list title; replace a `start` padding with `left`; remove
`windowInsetsPadding` from the list scaffold) and record the replacement
failing. Until then the grep unit tests `SecureWindowTest`,
`LargeFontLayoutTest`, `RtlLayoutTest` and `EdgeToEdgeInsetsTest` stay.

## Thread.sleep (R8)

`grep -rn 'Thread.sleep' app/src/androidTest vault/src/androidTest` after
merging main: 12 hits, each preceded by a `// P1-20-R8:` comment. Five are
simulated slow sources or measurement sampling intervals (the delay is the
behaviour under test: `IntakeScreenFlowTest` 52, `IntakeAndListFlowTest`
82, `OcrRepositoryTest` 338, `OcrEngineMeasurementTest` 66,
`OcrEvaluationTest` 209); seven are bounded polls of state with no flow or
callback the test can await (`AppLockFlowTest` 315, `ScriptedAuthenticator`
27, `IntakeActivityTest` 214, `IntakeAndListFlowTest` 331, 403, 565, 627).
Removed outright: the sleeps in `MultiWindowScrubTest` and
`NavigationBackTest`. The debug-only `TestHostileContentProvider` delays are
simulated slow providers, documented in its KDoc. The connected-suite
wall-clock before and after is not measured (no device).

## Not done or deviated

- R6: grep unit tests kept until their device replacements have run with a
  recorded mutation (only the unit `NavigationBackTest`, which asserted the
  old redirect design, was deleted; `ListDetailNavigationTest` replaces it
  on the JVM and the instrumented `NavigationBackTest` on the device).
- R7: the golden test exists; no PNG was generated or committed.
- Instrumented RED tests were written in GREEN: a device RED cannot be
  observed here.
- Release smoke, CI API 29/36 legs, the connected suite and its wall-clock
  duration: not run (owner policy for this run).
