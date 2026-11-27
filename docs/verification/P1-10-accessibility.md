# P1-10 Accessibility automation and TalkBack runbook

Branch `plan/P1-10-accessibility`, cut from main 77b4ebe. Date 2026-11-26.
Started by acting-owner authorisation while P0-07, P1-08 and P1-20 were at
`review`.

## Device precondition (read before any instrumented run)

The debug build uses application id `org.openlife`. The instrumented suites and
`adb shell pm clear org.openlife` destroy any OpenLife vault on the device,
including a real one. Confirm the device holds **no real OpenLife data** before
starting (`adb shell pm list packages | grep openlife` shows only the debug build
you install, and the owner has confirmed no vault is needed). The 2026-11-28 run
below used the attached tablet under the owner rule in `CLAUDE.md` ("Device
testing"); the policy text under "Device policy for this run" is the state as of
the first (2026-11-26) branch commit and is kept for history.

## Device policy for this run

Owner instruction (2026-11-24): no emulator, no `connected*AndroidTest`, no
`adb`. None was started or run. The instrumented tests below were
**compiled** (`./gradlew :app:compileDebugAndroidTestKotlin`) and **not
run**. Each one's status is **implemented; unverified (gap — pending
physical-device run)**. No Robolectric, no ATF and no other dependency was
added.

| Instrumented test | Command (run on the tablet `U8BBB20806201256` when it is free) |
| --- | --- |
| `ScreenSemanticsTest.everyScreenPassesAccessibilityChecks` (26 screen states x 3 sizes: 1x; 2x font; 2x font + largest display size) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.a11y.ScreenSemanticsTest#everyScreenPassesAccessibilityChecks` |
| `ScreenSemanticsTest.viewerEvidenceAnnouncementIsPoliteLiveRegion` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.a11y.ScreenSemanticsTest#viewerEvidenceAnnouncementIsPoliteLiveRegion` |
| Existing classes touched by changed semantics (`AccessibilitySemanticsTest`, `SettingsScreenTest`, `AboutScreenTest`, `SearchScreenTest`, `CoreFlowUxTest`, `BrandScreenshotInstrumentedTest`) | same command with each class |

Prioritised on-device checklist, in this order:

0. Precondition above: no real OpenLife data on the device.
1. `ScreenSemanticsTest` (both tests). Expect failures to be real; triage
   each before suppressing. A suppression needs a reason and an accessible
   equivalent (`AccessibilityRule.DEFAULT_SUPPRESSIONS`, target: empty).
2. `BrandScreenshotInstrumentedTest`: the Stale stamp label colour changed
   (no golden shows Stale; the others must be byte-identical). The empty-list
   golden includes the extracted `ImportActionContent`, which emits the same
   nodes.
3. `AccessibilitySemanticsTest`, `CoreFlowUxTest`: list rows are now
   `Role.Button` with the click label "Open"; tests that find rows by text
   are unaffected.
4. `docs/runbooks/talkback.md` (owner action OA-3), sections A, B, C.

## R1: the shared rule (deviation)

The step asks for `ComposeTestRule.enableAccessibilityChecks()`. In this
project's Compose BOM (ui-test 1.12.1), `ui-test-android` only exposes the
`ComposeAccessibilityValidator` hook. The ATF-backed implementation is the
separate artifact `androidx.compose.ui:ui-test-accessibility` (1.12.1 is
published, checked on Google Maven 2026-11-26). It brings
`com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework`
(latest 4.1.1). Neither is in the Gradle cache or in
`gradle/verification-metadata.xml`, and adding a dependency needs an owner
decision record. That decision is proposed, not taken.

Until then, `app/src/androidTest/java/org/openlife/app/a11y/AccessibilityRule.kt`
is a shared JUnit rule that reads the Compose semantics tree, the same tree
TalkBack reads. It runs after each test that uses it, or on demand, and
fails with every issue on the current screen. It checks:

| Check | ATF's nearest equivalent |
| --- | --- |
| actionable node has a spoken label | SpeakableTextPresentCheck |
| actionable node ≥ 48 x 48 dp | TouchTargetSizeCheck |
| image node has a description | ImageContentDescriptionCheck |
| editable field has a label | EditableContentDescCheck |
| toggle/selectable node states a role | ClassNameCheck |
| two click actions do not share bounds | DuplicateClickableBoundsCheck |
| text does not overflow its bounds (used at 2x) | TextSizeCheck (approx.) |

It does not check pixel contrast (the JVM `ThemeContrastTest` covers the
palette instead), traversal cycles or links. Suppressions, each with a
reason:

- `touch-target` under `viewer_evidence_canvas`: an evidence region is as
  large as the text it outlines. Each region has a 48 dp "Show region"
  button in the extracted-text panel that does the same thing.
- `clipped-text` under `search_result`: snippets are capped at four lines
  on purpose, and the result opens the whole text.

Swapping to ATF later means replacing `findIssues()` with
`enableAccessibilityChecks()`. The suppression list carries over.

## R2/R5: per-screen semantics (instrumented, compiled only)

`ScreenSemanticsTest.everyScreenPassesAccessibilityChecks` swaps one
composition through 26 synthetic screen states:
- the list (empty, with READY/CORRUPT/DELETING items, loading);
- vault-unavailable;
- the viewer (OCR ready, idle, running, failed and stale; transient read;
  unreadable item);
- intake (preparing, preview, saving, saved, duplicate, rejected, failed);
- first run, lock and the lock offer;
- settings, about, search (idle and results), and the delete dialog.

Each state is checked at three sizes: 1x; 2x font; and 2x font with
density x1.3, Android's "Largest" display size. Per state it asserts the
rule's checks, a pane title, a heading, and a polite live region where the
screen shows a status. At the large sizes it also asserts that every action
can be scrolled to and displayed, and that no text is clipped.

`LargeFontLayoutTest` (JVM, pass) now requires `verticalScroll`/insets
handling in VaultUnavailable, Search, Settings, About and Lock as well. It
also checks that `ScreenSemanticsTest` exercises all 11 screen composables
at `fontScale = 2f` and `LARGEST_DISPLAY_SIZE`. It does not run them.

Focus order (deviation): Compose orders TalkBack focus by geometry, not by
tree order, and the app sets no `traversalIndex`. A tree-order assertion
would not show what TalkBack does, so none was written. Reading order is
runbook rows A2, A13 and B3.

## JVM checks (run, pass)

Final gate at 2dfc505: `./gradlew detekt lint :app:test :vault:test assembleDebug
:vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`
=> BUILD SUCCESSFUL. App JVM 231/231 (baseline 211 on main 77b4ebe), vault
197/197, eval 26/26. `scripts/plan-check.sh`: steps=82 errors=0.
`scripts/tests/run.sh`: 51 passed, 0 failed.

| Test | What it proves | What it cannot prove |
| --- | --- | --- |
| `ThemeContrastTest` (7) | WCAG ratio for every Material on-role on its container, every content role (text 4.5, outline 3.0) on every surface role, brand ink/muted ink/attention on page surfaces, Ink on highlighter, every stamp label (4.5) and outline (3.0), light and dark. Role pairs come from the theme by reflection. A scan fails on any colour role app code uses that no rule covers, and on any brand colour inside a dialog or menu | Colours composed at runtime (alpha overlays, images), dynamic colour (not used), what is actually drawn |
| `AccessibilitySourceRulesTest` (10) | Source rules: icon/image descriptions or a decorative reason; clickable role, label and 48 dp minimum; headings on titles; no dp/pinned text size; no fixed height on text containers; gestures have custom actions; selection reaches semantics and highlights have a second cue; status/error texts are polite live regions; each screen has a resource pane title; accessibility text is not hard-coded | Layout, the merged tree, what TalkBack says, code reached through unusual paths. It reads text, not types |
| `LintAccessibilitySeverityTest` (1) | Lint's Accessibility category is `error` and lint.xml lowers none of its issues | AOSP accessibility lint barely sees Compose |
| `EvidenceGeometryTest.accessibilityZoomStepsDoubleAndHalveWithinTheLimits` | The zoom actions' steps | That TalkBack offers them (runbook A15–A17) |

RED: 12 of 29 targeted tests failed at commit 00d0dad (listed in the step's
`evidence`).

Mutation run (after GREEN): eight real
violations were put into shipped code at once, `AccessibilitySourceRulesTest`
ran, and the sources were restored with `git checkout`. All 10 rules failed,
each naming its mutation:

| Mutation | Rule(s) that failed, with the reported location |
| --- | --- |
| Decorative-icon comment dropped (`ImportActionContent`) | icons/images: `SourceListScreen.kt:311` |
| Search result `onClickLabel` dropped | clickables: `SearchScreen.kt:148` |
| `VaultUnavailableScreen` `asHeading()` dropped | headings: `VaultUnavailableScreen.kt:44` |
| OCR "Extracting" text: `announceStatus()` replaced by `fontSize = with(LocalDensity.current) { 14.dp.toSp() }` | font scale: `OcrPanel.kt:83 .toSp()`; status: `OcrPanel.kt:83` without a live region |
| `height(320.dp)` back on the intake preview box | fixed height: `IntakeScreen.kt:171` |
| `customActions =` renamed to an unused local | gestures: `ZoomableEvidenceImage.kt` |
| `this.selected = selected` dropped | colour alone: `ZoomableEvidenceImage.kt` selected flag never reaches semantics |
| About `paneTitle = "About"` (hard-coded) | pane title: `AboutScreen` has no resource paneTitle; hard-coded text: `AboutScreen.kt:49` |

An earlier, partly applied run showed that an import of `customActions`
alone satisfied the gesture rule, so the rule now requires an assignment
(commit 6c926eb).

## Defects found and changed

"Proof" says what has actually run. A source rule proves that the code is
present, not what TalkBack does. Everything marked as a gap waits for
`ScreenSemanticsTest` and the runbook on a device.

| Defect | Change | Proof |
| --- | --- | --- |
| Stale stamp label `#B7791F` 3.23:1 on paper (needs 4.5) | `attentionInk` `#8A5A10` (5.25:1); outline stays amber (VISUAL_IDENTITY §4 amended) | `ThemeContrastTest` passes (JVM); rendering: implemented; unverified (gap — pending physical-device run) |
| No pane titles: TalkBack did not announce screen changes | `paneTitle` from resources on 9 screen roots | source rule passes; behaviour: implemented; unverified (gap — pending physical-device run) |
| Titles not headings (bars, dialogs, display/headline/title text; 19 places) | `Modifier.asHeading()` | source rule passes; behaviour: implemented; unverified (gap — pending physical-device run) |
| Status and error text not announced (OCR running/failed/cancelled/stale, every intake state, verify-all, reset failure, lock note and refusals, About document failure, list loading) | `Modifier.announceStatus()` (polite) | source rule passes; behaviour: implemented; unverified (gap — pending physical-device run) |
| List row and OCR span row clickable had no role, label or minimum height | `Role.Button`, "Open" / "Show in image", `heightIn(min = 48.dp)` | source rule passes; size: implemented; unverified (gap — pending physical-device run) |
| Search result had no click label | "Open in viewer" | source rule passes; behaviour: implemented; unverified (gap — pending physical-device run) |
| Pinch and double-tap zoom had no accessible equivalent | "Zoom in", "Zoom out", "Fit to screen" custom actions and a "Zoom: N%" state on the image (read as "percent") | step arithmetic: `EvidenceGeometryTest` passes (JVM); actions: implemented; unverified (gap — pending physical-device run) |
| Selected evidence region shown by fill colour only | `selected` semantics | source rule passes; behaviour: implemented; unverified (gap — pending physical-device run) |
| Intake preview text in a fixed 320 dp box (clips at 2x) | the fixed height is only on the image; the box is `heightIn(min = 320.dp)` | source rule passes; layout at 2x: implemented; unverified (gap — pending physical-device run) |
| Two decorative `+` icons had no stated reason | comment; shared `ImportActionContent` | source rule passes |

Found and not fixed (owner decisions or out of scope, in the step's
`notes`): the list's Delete buttons say "Delete" without the item name;
OCR history toggle has no expanded/collapsed state; correction and search
fields set both a label and a content description (possible double
announcement; runbook A22, A30); panning a zoomed image has no accessible
equivalent other than "Show region" and "Fit to screen".

## Not changed

FLAG_SECURE stays on every window. No content goes to notifications, logs or
the window title. The new strings are generic. The one pre-existing debug
log, `EvidenceBounds`, holds geometry only.

## Review fixes (2026-11-27)

Commits on `plan/P1-10-accessibility` after the first record (from `git log`):

- **Stable status host** (3e14ba2, 5c70475 RED, 1230541, 3debf17): every screen
  announces status through one stable polite live node. Status is no longer a
  live region that is added and removed with the status text. The source rule
  now requires `Announce` and forbids `liveRegion` outside the host.
- **OCR history toggle state** (96d05e1): the toggle label now names the action
  and the state ("Show history (N)" / "Hide history (N)"), so TalkBack hears the
  state. The button does not expose an expanded/collapsed role.
- **List title autosize** (e4d9b98, 4370061): the list title is one ellipsised
  line and scales down with a `StepBased` autosize, minimum 12 sp. Visual
  acceptability of the scaled title has **not been judged** by anyone.
- **Delete labels** (f578cb6): each list row's delete action and the viewer's
  delete action name the item: "Delete <item label>". The item label is the
  import timestamp ("Imported <date and time>"), not document content.
- **Zoom announcements off the pinch path** (a20c259): zoom state and the zoom
  custom actions are kept off the pinch gesture path, and span targets are kept
  off it too.
- **Waits in ScreenSemanticsTest** (182de14): the test waits for decoded images
  and for the screen to settle before it runs the checks, instead of checking
  immediately.
- **`intake_preview_image` test tag added to production source** (182de14,
  `app/src/main/java/org/openlife/app/ui/IntakeScreen.kt`): a test tag on the
  intake preview image, so the ScreenSemanticsTest case can find it. Production
  source changed for a test tag only.
- Lint and detekt/ktlint fixes for these changes (9d7bad5, 3debf17) and the
  large-font clipping failure message (c4764bb).

Gate after the merge (2026-11-27): see the step's `evidence` in
`plan/plan.yaml`. The merge of `origin/main` was a no-op (`Already up to date`).

## Device run 2026-11-28, Huawei AGS2-W09, API 26

Device: Huawei AGS2-W09, Android 8.0.0 (API 26), adb serial `U8BBB20806201256`.
No `org.openlife*` package was installed before the run (`pm list packages`).
Command: `./gradlew :app:connectedDebugAndroidTest --continue` (full app suite),
`ANDROID_SERIAL=U8BBB20806201256`. Gradle: `BUILD FAILED in 7m 8s`, exit 1.
`stay_on_while_plugged_in` was set to 3 for the run and restored to 0 afterwards.

Totals (parsed from `TEST-AGS2-W09 - 8.0.0.xml`, copied to
`scratchpad/device-logs/p110-app-full-results/`): **148 tests, 8 assertion
failures, 4 assumption skips reported as failures, 0 errors, 0 skipped**.
The four "AssumptionViolatedException" entries are skips, not failures.

Failures as observed (class.method, first line of the message):

1. `org.openlife.app.a11y.ScreenSemanticsTest.everyScreenPassesAccessibilityChecks`:
   `accessibility failures:` (236 findings; see Open findings).
2. `org.openlife.app.ui.EdgeToEdgeUiTest.contentClearsSystemBarsWithLargeInsets`:
   `the title is under the status bar`.
3. `org.openlife.app.ui.EvidenceNavigationTest.tappingASpanHighlightsItsRegionWithinTheImageBounds`:
   `Action performScrollTo() failed.`
4. `org.openlife.app.ui.EvidenceNavigationTest.tappingInsideARegionSelectsItsSpanAndAnnounces`:
   `Action performScrollTo() failed.`
5. `org.openlife.app.ui.EvidenceNavigationTest.tapAtThreeTimesSelectsTheSpanUnderTheFinger`:
   `Action performScrollTo() failed.`
6. `org.openlife.app.ui.LargeFontUiTest.noControlIsClippedAtFontScaleTwoOn320x640`:
   `list: a control is clipped horizontally: 'null' 620 x 112 Rect.fromLTRB(0.0, 1136.0, 608.0, 1248.0)`.
   The message names the control as `'null'`, so the control name added in
   c4764bb did not appear in this run's message. The control is not identified
   from this output.
7. `org.openlife.app.ui.search.SearchScreenTest.expandedResultSelectsTheSpanInTheDetailPaneBesideTheList`:
   `Failed to assert the following: (Text + InputText + EditableText contains 'Pick up by Friday' ...)`.
8. `org.openlife.app.ui.search.SearchScreenTest.compactResultOpensTheViewerRouteWithTheSpanSelectedAndBackReturnsToResults`:
   same message as 7.
9. Assumption skip: `org.openlife.app.lock.AppLockFlowTest.deviceCredentialUnlockBelowApi30KeepsThePendingShare`:
   `skipped: sets a device PIN; pass -e allowDevicePinChange true to opt in (emulators only)`.
10. Assumption skip: `org.openlife.app.ui.brand.BrandScreenshotInstrumentedTest.listDetailExpandedMatchesGolden`:
    `no committed list-detail goldens for render profile api26-AGS2-W09EEA-320dpi`.
11. Assumption skip: `BrandScreenshotInstrumentedTest.deleteDialogMatchesGolden`: `no committed delete-dialog goldens for render profile api26-AGS2-W09EEA-320dpi`.
12. Assumption skip: `BrandScreenshotInstrumentedTest.listViewerFirstRunMatchGoldensLightAndDark`: `no committed goldens for render profile api26-AGS2-W09EEA-320dpi`.

Not run or not asserted on this device: the golden comparisons (no goldens for
this render profile) and the device PIN lock flow (opt-in, emulators only).

## Open findings

### ScreenSemanticsTest findings from the device run

`everyScreenPassesAccessibilityChecks` reported **236 findings**: **233
clipped-text** and **3 touch-target**. The 3 touch-target findings are at 1x
(DEFAULT). All 233 clipped-text findings are at 2x font, with or without the
largest display size. Counts are the message's own lines, grouped by rule and
screen state:

Rule totals: clipped-text 233, touch-target 3.

touch-target (all at 1x, screen `viewer-ocr-ready`):
- `Accept text`, 196 x 80 px (below 48 dp at this density).
- `Reject`, 129 x 80 px.
- `Run again`, 173 x 80 px.

clipped-text (all at 2x font; the second column is 2x font with the largest
display size). Counts per screen state and size:

| Screen state | 1x | 2x font | 2x font + largest display |
|---|---|---|---|
| viewer-ocr-ready | 3 | 21 | 19 |
| list-empty | - | 2 | 2 |
| list-items | - | 4 | 3 |
| list-loading | - | 1 | 1 |
| vault-unavailable | - | 4 | 4 |
| viewer-ocr-idle | - | 8 | 6 |
| viewer-ocr-running | - | 9 | 7 |
| viewer-ocr-failed | - | 8 | 6 |
| viewer-ocr-stale | - | 8 | 6 |
| viewer-transient | - | 8 | 6 |
| viewer-unreadable | - | 5 | 4 |
| intake-preparing | - | 2 | 2 |
| intake-preview | - | 3 | 3 |
| intake-saving | - | 1 | 1 |
| intake-saved | - | 2 | 2 |
| intake-duplicate | - | 2 | 2 |
| intake-rejected | - | 2 | 2 |
| intake-failed | - | 2 | 2 |
| first-run | - | 3 | 2 |
| lock | - | 3 | 3 |
| lock-offer | - | 3 | 3 |
| settings | - | 5 | 5 |
| about | - | 11 | 11 |
| search-idle | - | 2 | 2 |
| search-results | - | 2 | 2 |
| delete-dialog | - | 3 | 3 |

Examples from the message: "Nothing kept yet", "Import from photos", "Content
unavailable", "Deletion pending — retry", "Loading…", "The vault is unavailable",
"What this means", "Try again", "Open Settings", "Extracted text", "Review: Unreviewed",
"Show region", "Correct", "Reject span", "Undo reject", "Accept text", "Reject". Each
finding is a node whose text overflows its measured bounds. No triage has been done yet; each finding needs a
decision before the check is suppressed or the layout is changed.

Other known findings (device run, observed):
- The extended floating action button "Import from photos" is clipped
  horizontally at 200 % font on a 320 x 640 dp window (LargeFontUiTest, failure
  6 above). The control name is not in that message (it reads `'null'`).
- `EdgeToEdgeUiTest`: the title is under the status bar with large insets.
- Search screen: expanded and compact result tests cannot find the text
  "Pick up by Friday" (failures 7 and 8).
- Evidence navigation: `performScrollTo()` fails for three tests (failures 3-5).

### Other open items

- **Default focus indicator**: the Material default focus indicator is about
  1.2:1 against the paper surface. Keyboard users cannot see focus well enough.
  Not fixed.
- **No accessible pan alternative for zoomed images** (WCAG 2.5.7): only "Show
  region" and "Fit to screen" exist. Zoom in and zoom out are actions, but there
  is no pan action.
- **Text fields possibly announced twice** (correction, search and reset-word
  fields set both a label and a content description). Device check pending
  (runbook A22, A30).
- **Accessibility Test Framework (requirement R1) deferred.** The step asks for
  `enableAccessibilityChecks()`, which needs
  `androidx.compose.ui:ui-test-accessibility` and the ATF artifact. Neither is
  in the Gradle verification metadata, so a new dependency decision record is
  needed. Until that is decided, `AccessibilityRule` is the in-house substitute.
  **R1 remains a deviation.** A decision record is a follow-up.
- **Acceptance line not met**: "AccessibilityChecks pass on both CI legs" is not
  met. The emulator legs are manual-dispatch only by owner instruction (CI
  emulator legs run on manual dispatch only), so no CI leg has run for this step.
- **Known bypasses of the source rules**: the source rules read text, not
  types, so these forms can evade them: a conditional
  `contentDescription = if (x) null else d`; extension wrappers around
  `clickable`; `ProvideTextStyle`; a positional `Density(d, 1f)`; named-constant
  heights; a per-file gesture check; colour chosen by `if`; screens missing from
  the `SCREENS` list.
- **Lint setting has no effect in practice**: lint's Accessibility category is
  set to error, but warnings already failed the build, so the change alters
  nothing in practice.


## Regression fixes (2026-11-28)

**Cause.** The selected-span line in the viewer had been moved into a 1 dp
accessibility-only node (`StatusHost(spanNode = true, spanSelection = ...)`,
tag `viewer_span_announcement`). Sighted users lost the visible line, and
TalkBack read the span text from the hidden node. The line is restored as
visible `Text`; span text is removed from every announcement. Status
announcements never carry document content (OCR text, span text, file names,
queries).

**Review fix list, as applied** (one commit each on `plan/P1-10-accessibility`):

1. ViewerScreen: `StatusHost { ... }` with no span parameters. The visible
   "Selected: ..." `Text` has no live region.
2. AccessibilitySemantics: `spanNode`, `spanSelection`, the second `LiveNode`
   and `SPAN_SELECTION_NODE_TAG` are removed. The androidTest checks for the
   tag now assert the visible text instead (ScreenSemanticsTest,
   EvidenceNavigationTest, SavedStateTest, search/SearchScreenTest).
3. The host node holds a message for 4 s (`LaunchedEffect` keyed on the
   message; `StatusChannel.expire` clears only that exact message). The visible
   Text is not hidden and `invisibleToUser` is not used. JVM test:
   `StatusChannelTest` (3 tests, passing).
4. SourceListScreen: `TITLE_MIN_FONT_SIZE` 12 sp -> 16 sp. The title still
   ellipsizes on one line below that.
5. VaultUnavailableScreen: the `StatusHost` wrapper is removed (nothing there
   calls `Announce`).
6. AccessibilitySourceRulesTest: `statusIsPostedOnlyThroughTheStableStatusHost`
   is kept; new rule `announcementArgumentsAreNeverDocumentContent` fails on
   any `Announce(...)` argument that names span, OCR, file-name or query data
   and fails if no `Announce` call is found.

Also in this round: the OCR panel's text buttons have a 48 dp touch target
(commit a16293c). The device check below shows this is not yet met in the
accessibility check; see the failures.

**Gate.** `./gradlew detekt lint :app:test :vault:test assembleDebug
:vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin
:eval:test`: BUILD SUCCESSFUL (run after the detekt fixes in the last gate
commit). `scripts/plan-check.sh`: `steps=83 errors=0`.

**Device (Huawei AGS2-W09, Android 8.0, serial U8BBB20806201256).** Full run
`./gradlew :app:connectedDebugAndroidTest --continue`, log
`device-logs/p110c-app-full.log`, results `device-logs/p110c-results/`.
Totals: 148 tests, 140 passed, 8 failed. Four of the eight are JUnit
assumptions that stop the test on this device (AppLock 1, Brand 3); the XML
records them as failures. The SearchScreenTest fix was committed after the
gate run, and its class was re-run on the tablet (2 tests, 0 failures); the
gate was not re-run after that commit.
Main's run (`device-logs/app-results-run6/`) had 146 tests, 140 passed, 6 failed.

Failures, each marked against main:

- `ScreenSemanticsTest.everyScreenPassesAccessibilityChecks`: touch-target
  failure on `viewer-ocr-ready`: "Accept text" 196x80 px and "Reject" 129x80 px.
  At 320 dpi, 80 px is 40 dp, below the 48 dp target. The main run has no
  entry for this test (it is new on this branch), so it is not a regression by
  the comparison rule, but the 48 dp change is not yet satisfied. Open.
- `AppLockFlowTest.deviceCredentialUnlockBelowApi30KeepsThePendingShare`:
  skipped by assumption ("sets a device PIN"). Also fails on main.
- `EdgeToEdgeUiTest.contentClearsSystemBarsWithLargeInsets`: "the title is
  under the status bar". Also fails on main.
- `LargeFontUiTest.noControlIsClippedAtFontScaleTwoOn320x640`: list control
  clipped horizontally. Also fails on main.
- `BrandScreenshotInstrumentedTest` (3 tests): assumption, no committed goldens
  for render profile api26-AGS2-W09EEA-320dpi. Also fail on main.
- `SearchScreenTest.expandedResultSelectsTheSpanInTheDetailPaneBesideTheList`:
  "Selected: Pick up by Friday" not displayed at the assertion. This was a
  regression against main. Fixed in the test (scroll to the line first, as
  `EvidenceNavigationTest` does). Re-run of the class on the tablet:
  2 tests, 0 failures.

The renamed `ScreenSemanticsTest.viewerSelectedSpanIsVisibleTextNotALiveRegion`
has no main entry (it replaces the old live-region test). Its result is in the
full run and is not a failure.
