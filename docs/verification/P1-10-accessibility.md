# P1-10 Accessibility automation and TalkBack runbook

Branch `plan/P1-10-accessibility`, cut from main 77b4ebe. Date 2026-11-26.
Started by acting-owner authorisation while P0-07, P1-08 and P1-20 were at
`review`.

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
`evidence`). Mutation run (after GREEN): eight real violations were put into shipped
code at once: decorative-icon comment dropped (SourceListScreen); search
`onClickLabel` dropped; VaultUnavailable `asHeading()` dropped; a pinned
14 sp font size on OCR text; `height(320.dp)` back on the intake box;
`customActions` renamed; `this.selected` dropped; About `paneTitle = "About"`.
`AccessibilitySourceRulesTest` then failed 10/10, and the sources were
restored with `git checkout`. An earlier, partly applied run showed that
an import of `customActions` alone satisfied the gesture rule, so the rule
now requires an assignment (commit 6c926eb).

## Defects found and fixed

| Defect | Fix |
| --- | --- |
| Stale stamp label `#B7791F` 3.23:1 on paper (needs 4.5) | `attentionInk` `#8A5A10` (5.25:1); outline stays amber (VISUAL_IDENTITY §4 amended) |
| No pane titles: TalkBack did not announce screen changes | fixed `paneTitle` from resources on 9 screen roots |
| Titles not headings (bars, dialogs, display/headline/title text; 19 places) | `Modifier.asHeading()` |
| Status and error text not announced (OCR running/failed/cancelled/stale, every intake state, verify-all, reset failure, lock note and refusals, About document failure, list loading) | `Modifier.announceStatus()` (polite) |
| List row and OCR span row clickable had no role, label or minimum height | `Role.Button`, "Open" / "Show in image", `heightIn(min = 48.dp)` |
| Search result had no click label | "Open in viewer" |
| Pinch and double-tap zoom had no accessible equivalent | "Zoom in", "Zoom out", "Fit to screen" custom actions and a "Zoom: N%" state on the image (read as "percent") |
| Selected evidence region shown by fill colour only | `selected` semantics |
| Intake preview text in a fixed 320 dp box (clips at 2x) | fixed height only on the image; the box is `heightIn(min = 320.dp)` |
| Two decorative `+` icons had no stated reason | comment; shared `ImportActionContent` |

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
