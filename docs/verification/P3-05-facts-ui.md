# P3-05 verification: facts UI, user-provided facts, inert values

Branch `plan/P3-05-facts-ui` (worktree). Commits on the branch after `943a006`:
item 1 title and fill-width `4413df0`; DATE red `97c591d`, green `ddb3268`; TIME red `7bb5cf9`, green `9798e40`; MONEY red `cb02280`, green `46358b7`; host tests for item 3 `66946ce`; detekt and heading-rule fixes `a38f7ff`; review action row `01180c1`.
Date of run: 2026-11-28. Final code: `01180c1`. Not pushed.

## Commands and real results

| Command | Result |
| --- | --- |
| `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test` on `01180c1` (log `scratchpad/gate6.log`) | `BUILD SUCCESSFUL in 3m 2s`, exit 0 |
| Same gate on `a38f7ff` (log `scratchpad/gate5.log`) | `BUILD SUCCESSFUL in 3m 46s` |
| `scripts/plan-check.sh` (in the worktree) | `steps=83 errors=0`, `runnable now: []` |
| `bash scripts/tests/run.sh` | `51 passed, 0 failed` |
| `./gradlew :vault:testDebugUnitTest --tests '...FactWriterTest' --tests '...FactExtractionCoreTest'` (before `66946ce`) | FactWriterTest 8 tests, 0 failures; FactExtractionCoreTest 9 tests, 0 failures |

Gate history: the first gate run failed on detekt (line length, import order, `TooManyFunctions` in `FactsPresenter`, unused imports, brace-less `if`). `AccessibilitySourceRulesTest.titlesAreMarkedAsHeadings` then flagged two title slots; fixed in `a38f7ff`.

## Requirement to test

| Req | Host / JVM tests | Device tests (tablet `U8BBB20806201256`, Android 8.0.0) |
| --- | --- | --- |
| R1 panel, origin and status badges, Show evidence, clarification chooser | `FactsPresenterTest` (JVM, 16 cases incl. candidate choice); `FactWriterTest` (existing clarify path) | `FactsScreenTest.showsOriginAndStatusBadgesAndEvidenceNavigation`; `FactsFlowTest.clarifyChoosingACandidateSendsThatCandidateOnly`, `rejectSendsTheRejectDecisionForThatRevision`, `evidenceActionOpensTheViewerWithTheSpanSelected`, `viewerFactsEntryOpensTheFactsOfThatSource` |
| R2 Add a fact (date, time, amount with code, text) creates a USER revision with createdAt and no evidence | `FactWriterTest.addingAUserFactOfEachKindCreatesAConfirmedUserRevisionWithNoEvidence` (added `66946ce`; URL, EMAIL, PHONE, DATE, TIME, MONEY); `UserValueNormaliserTest.noDateChosenIsRefusedWithNoValue`; `FactsPresenterTest`: picked date, no date, same day in every zone, picked time, no time, amount with currency, amount without currency | `FactsFlowTest.addingAUserDateSavesTheChosenDayWithNoEvidence`, `addingAUserTimeSavesTheChosenTimeWithNoEvidence`, `addingAUserAmountSavesTheAmountAndCurrencyWithNoEvidence`, `addingAUserUrlSendsTheNormalisedValueToTheGateway`, `addingABlankUserValueIsRefusedAndNeverReachesTheGateway` |
| R3 URLs, emails, phones inert, with the no-open note | source check: `ui/facts` contains no `ClickableText`, `LinkAnnotation`, `SelectionContainer`, `textSelectable` or `clickable` (grep, this step) | `FactsInertValuesTest` (1 case) |
| R4 stale greyed with its sentence, Find facts again | `FactDisplayTest.aStaleFactSaysWhyInItsOwnWords`; `FactExtractionCoreTest.findAgainRunsOnTheCurrentReadingAndNeverOnAnOlderOne` (added `66946ce`) | `FactsFlowTest.staleFactIsGreyedWithItsSentenceAndFindFactsAgainRunsExtraction` |
| R5 accessibility and large-font | `ThemeContrastTest`, `AccessibilitySourceRulesTest` (incl. heading rule, passing after `a38f7ff`) | `LargeFontUiTest` (facts route passes); `ScreenSemanticsTest` (see device totals) |
| R6 locale formatting, canonical value unchanged | `FactDisplayTest.aDateIsFormattedForTheLocaleAndTheStoredValueIsUnchanged` and three further `FactDisplayTest` cases (time, amount, unparseable value) | none |

Picker state is held with `remember`, not `rememberSaveable` (source check: `AddFactSheet.kt` lines 93-94 and 160-161). The two comments there say why, so a picked value never reaches saved instance state.

## Device totals (final code `01180c1`)

Each class was run alone with `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` and its XML read after each run.

| Class | Run on | Result |
| --- | --- | --- |
| FactsFlowTest | `01180c1` (06:00) | 10 tests, 0 failures. Before the fix in `01180c1` the first run had 1 failure (`rejectSendsTheRejectDecisionForThatRevision`, `waitUntil` timeout): the second button of the Confirm/Reject row had no bounds. |
| FactsInertValuesTest | `01180c1` (06:01) | 1 test, 0 failures |
| LargeFontUiTest | `01180c1` (06:02) | 2 tests, 1 failure. Known: `noControlIsClippedAtFontScaleTwoOn320x640`, list route ("a control is clipped horizontally"). The facts case passes. |
| ScreenSemanticsTest | `01180c1` (06:03) | 2 tests, 1 failure. Known: `everyScreenPassesAccessibilityChecks`. The facts-related findings are listed below. |
| FactsScreenTest | `a38f7ff` (05:57): 1 pass. `01180c1`: blocked (see gaps). | Final-code run not obtained |

Totals on `01180c1`: 15 tests executed (FactsFlowTest 10, FactsInertValuesTest 1, LargeFontUiTest 2, ScreenSemanticsTest 2); 13 passed, 2 failed (both known, not facts-specific), 1 class (FactsScreenTest) blocked.

Facts-route findings in ScreenSemanticsTest (all `[clipped-text]`, text overflows its bounds, unchanged by this step):
- `facts-ready @ FONT_2X`: 15 findings. Web address; https://example.org; OpenLife does not open links or copy them; Found in the text; Confirmed; Date; 3 Apr 2026; The text this came from changed; Found in the text; Out of date; Date; 2026-04-03 | 2026-03-04; Found in the text; Needs clarification; Which value is right?
- `facts-ready @ FONT_2X_LARGEST_DISPLAY`: 9 findings. Web address; https://example.org; Found in the text; Confirmed; Date; 3 Apr 2026; The text this came from changed; Found in the text; Out of date.

The 200 % title fix (item 1) is confirmed by the facts case of LargeFontUiTest; the title does not appear in the findings.

## Gaps and open questions

1. **FactsScreenTest on the final code is not verified.** It passed at 05:57 on `a38f7ff`. Later runs failed with `No compose hierarchies found` (06:08, 06:08 again, 06:11). The cause is device state, not code: the screen had gone to sleep after `stay_on_while_plugged_in` was set to 0, and the keyguard was then showing (`isStatusBarKeyguard=true`). The same failure occurred with the pre-`01180c1` FactsPanel. The lock was not dismissed because the lock credentials are out of scope. The class must be re-run on the tablet when it is unlocked.
2. **Earlier-reading evidence cannot be opened (Q-15).** The read-only viewer mode is not built. An evidence row on an earlier reading shows the note "From an earlier reading of this source, so it cannot be shown in the text" and no Show evidence button (`FactsPanel.kt`). No test covers that branch.
3. **"Correct this value" (C2-R37) is not offered.** The UI has no such action.
4. **CAD is not offered** in the money chooser. The line scanner gives no single value for "10 CAD" (C2 normalisation limit). The other 10 codes are offered.
5. **Money without a code is refused** (`MONEY_NEEDS_CODE`). ADR-0008 stores a null currency only with a printed symbol, so "Not stated" cannot be stored. OPEN QUESTION for the owner: allow a typed symbol, or remove "Not stated" from C2-R38.
6. **UI tests use `FakeFactsGateway`.** Persistence is proven by the host tests in item 3 (`FactWriterTest`, `FactExtractionCoreTest`). The Room repository wrapper (`FactRepository`, `MutationQueue`) is covered on device only by `FactExtractionRepositoryTest`, which was not re-run in this step.
7. **Inert values and selection** are checked at source level only (no link or selection API in `ui/facts`). No device test covers text selection.
8. **Remaining 2x clipped-text findings:** 24 on the facts route (listed above), plus the known list-route failures. These are not fixed in this step.
9. **R6 locale** is covered by JVM tests only. No device test switches locale.
10. **Dependency order.** `depends_on` P3-04 and P1-20 are both `review`, not `done`. The coordinating session directed this step to proceed. The owner should confirm the order before review.
11. **Test-environment note.** `stay_on_while_plugged_in` was set to 7 for the late FactsScreenTest reruns and reset to 0 afterwards. The screen timed out after the reset, which caused gap 1.
