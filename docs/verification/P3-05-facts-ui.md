# P3-05 verification: facts UI, user-provided facts, inert values

Branch `plan/P3-05-facts-ui` (worktree). Not pushed. Status stays `review`.
HEAD after this run: `5488566`. Date of run: 2026-11-28.

Commits on the branch after `01180c1` (the previous verified code):

| Item | Commits |
| --- | --- |
| A, Correct this value (C2-R37) | `7c7c2d3` RED, `ee3d88d` GREEN |
| B, earlier reading read-only viewer and evidence routing (C2-R39) | `64925cc` RED, `1351d4a` RED stub, `ba13ff8` GREEN state, `5ffd3b0` RED routing, `c2664bc` GREEN |
| C, clarification re-choice (C2-R36) | `0f5c767` RED vault, `d9b428c` RED app, `3dcdef4` GREEN |
| Detekt fixes for A to C | `0bccd96` |
| Device test fixture order (C2-R36 test) | `5488566` |

Earlier history (`943a006` to `01180c1`): item 1 title and fill-width, DATE, TIME and MONEY input with red and green commits, host tests `66946ce`, heading-rule fixes `a38f7ff`, review action row `01180c1`.

## Commands and real results

| Command | Result |
| --- | --- |
| `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test` on the tree of `0bccd96` (log `scratchpad/gate-d2.log`) | `BUILD SUCCESSFUL in 4m 47s`, exit 0 |
| `scripts/plan-check.sh` (log `scratchpad/plan-check.log`) | `steps=83 errors=0`, `runnable now: []`, exit 0 |
| `bash scripts/tests/run.sh` (log `scratchpad/tests-run.log`) | `51 passed, 0 failed`, exit 0 |

The only change after the gate is the androidTest fixture in `5488566`, which does not touch production code or any `src/main` source. The gate was not re-run for it.

Gate history for this session: the first run of the gate failed on detekt (16 findings: `MultiLineIfElse` in OcrPanel, ViewerImage and ViewerScreen; `FunctionSignature` in VaultFactsGateway and FactsPresenter; `MaxLineLength` in FactsPresenterTest; `TooManyFunctions` in FactWriter, FactsPresenter, OcrPresenter, ViewerSession and FactsPanel). Those were fixed in `0bccd96`, and the gate then passed.

## Requirement to test

| Req | Host / JVM tests | Device tests (tablet `U8BBB20806201256`, Android 8.0.0, API 26) |
| --- | --- | --- |
| R1 panel, badges, Show evidence, chooser | `FactsPresenterTest` (JVM, cases for candidate choice, correction and evidence); `FactWriterTest` (clarify, correct, candidatesOf) | `FactsScreenTest`, `FactsFlowTest` (see device table below) |
| R1, C2-R37 Correct this value | `FactsPresenterTest` correction cases (same input as Add a fact, refusal never reaches the gateway); `FactWriterTest` correction cases | `FactsFlowTest.correctingAUserUrlSendsTheRevisionAndTheNormalisedValue` |
| R1, C2-R39 earlier reading, read-only | `ViewerSessionTest`, `OcrViewModelTest` (readingState, readOnly) | `EarlierReadingViewerTest` (2 tests); `FactsFlowTest.anEarlierReadingEvidenceOpensThatReadingAndNoOther` |
| R1, C2-R36 re-choice | `FactWriterTest.candidatesAreReadForTheExtractedRevisionAndEveryClarificationOfIt` (candidates read for the extracted revision and for each clarification; null once superseded or STALE); `FactsPresenterTest.aClarifiedValueOffersItsCandidatesAndRechoosingSendsOneOfThem`; `FactWriterTest.clarificationChoosesOneCandidateByteForByte` (existing) | `FactsFlowTest.aClarifiedChoiceOffersTheOtherCandidatesAndSendsTheChosenOne`, `FactsFlowTest.clarifyChoosingACandidateSendsThatCandidateOnly` |
| R2 Add a fact | `FactWriterTest.addingAUserFactOfEachKindCreatesAConfirmedUserRevisionWithNoEvidence`; `UserValueNormaliserTest`; `FactsPresenterTest` picker cases | `FactsFlowTest` add cases (date, time, amount, URL, blank refused) |
| R3 inert values | source check: no `ClickableText`, `LinkAnnotation`, `SelectionContainer`, `textSelectable` or `clickable` in `ui/facts` | `FactsInertValuesTest` (run on `01180c1`, not re-run here) |
| R4 stale and Find facts again | `FactDisplayTest.aStaleFactSaysWhyInItsOwnWords`; `FactExtractionCoreTest.findAgainRunsOnTheCurrentReadingAndNeverOnAnOlderOne` | `FactsFlowTest.staleFactIsGreyedWithItsSentenceAndFindFactsAgainRunsExtraction` |
| R5 accessibility and large font | `ThemeContrastTest`, `AccessibilitySourceRulesTest` | `LargeFontUiTest`, `ScreenSemanticsTest` (run on `01180c1`, not re-run here; see gaps) |
| R6 locale | `FactDisplayTest` (locale formatting, canonical value unchanged) | none |

## Device totals (this session, one class per invocation, XML read after each)

All runs used `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` on the tablet. The XML is under `app/build/outputs/androidTest-results/connected/debug/`. Before each run, `stay_on_while_plugged_in` was set to 3 and the tablet was woken and the keyguard dismissed. It was set back to 0 after the last run and read back as 0.

| Class | Code | Result (XML) |
| --- | --- | --- |
| FactsFlowTest | `5488566` | 13 tests, 0 failures, 0 errors. Second run. The first run on `0bccd96` had 12 passing and 1 failing: `aClarifiedChoiceOffersTheOtherCandidatesAndSendsTheChosenOne` timed out at line 216 (`waitUntil` on the clarify call). Cause: the test fixture paired the text `2026-04-03` with the entry `2026-03-04`, which is the value in use, so that button was disabled. The fixture text order was corrected in `5488566`. |
| FactsScreenTest | `5488566` | 1 test, 0 failures |
| EarlierReadingViewerTest | `5488566` | 2 tests, 0 failures |
| ViewerScreenTest | `5488566` | 3 tests, 0 failures |
| ViewerScreenC1Test | `5488566` | 10 tests, 0 failures |
| EvidenceNavigationTest | `5488566` | 8 tests, 0 failures |

Totals this session: 37 device tests, 37 passed, 0 failed.

Not re-run this session: `FactsInertValuesTest`, `LargeFontUiTest` and `ScreenSemanticsTest`. On `01180c1` they gave 1 pass, 2 tests with 1 known failure (`noControlIsClippedAtFontScaleTwoOn320x640`, list route), and 2 tests with 1 known failure (`everyScreenPassesAccessibilityChecks`; 24 facts-route clipped-text findings, listed below).

Facts-route findings in ScreenSemanticsTest (all `[clipped-text]`, from `01180c1`, not fixed in this step):
- `facts-ready @ FONT_2X`: 15 findings.
- `facts-ready @ FONT_2X_LARGEST_DISPLAY`: 9 findings.

Device RED: the instrumented RED for items B and C was written, and the androidTest sources compile, but the RED commits were not run on the tablet before their GREEN commits. The C device test first failed for the fixture reason above, not for a missing behaviour, so it did not serve as RED evidence. The behaviour is proven by the JVM and host tests in the table.

## Gaps and open questions

1. **Not re-run on the tablet this session**: `FactsInertValuesTest`, `LargeFontUiTest`, `ScreenSemanticsTest`. The facts-route clipped-text findings stand from `01180c1`.
2. **DATETIME has no input form**, so "Correct this value" is not offered for DATETIME.
3. **USER BASIS "You chose this from:"** is not implemented (out of scope for this step).
4. **No DB-level host test** for `OcrRepository.observeOcrViewOfRevision`. The earlier-reading path is proven at JVM and device level only.
5. **Money without a currency code** is refused (`MONEY_NEEDS_CODE`). ADR-0008 stores a null currency only with a printed symbol, so "Not stated" cannot be stored. OPEN QUESTION for the owner: allow a typed symbol, or remove "Not stated" from C2-R38.
6. **CAD is not offered** in the money chooser (the line scanner gives no single value for "10 CAD").
7. **UI tests use `FakeFactsGateway`.** Persistence is proven by host tests. The Room wrapper `FactRepository` is covered on device only by `FactExtractionRepositoryTest`, not re-run here.
8. **Dependency order.** `depends_on` P3-04 and P1-20 are both `review`, not `done`. The coordinating session directed this step to proceed. The owner should confirm before review.
9. **Locale (R6)** is covered by JVM tests only. No device test switches locale.
10. **Viewer and search** (handed-over findings a and b in the step notes) are not changed here; they belong to P3-07.

## Review fixes U-01..U-06 and cleanup

Commits on `plan/P3-05-facts-ui` (not pushed): `b2e8441` U-01, `eb9757d` U-02 and U-03, `39e1251` U-06,
`a9dd719` U-05, `6fedd04` cleanup, `872c558` U-04, `edddeae` detekt formatting.

| Finding | Fix | Tests | Result |
| --- | --- | --- | --- |
| U-01 a reading of another source is shown | `findRevisionOfSource` in the DAO; `Unavailable` state | `OcrViewModelTest.aReadingOfAnotherSourceIsUnavailableNotShown` (JVM); `EarlierReadingViewerTest.readingOfAnotherSourceIsRejected` (device); `OcrRepositoryTest.observeOcrViewOfRevisionRefusesAReadingOfAnotherSource` (vault androidTest, compiled, not run on device) | JVM and device pass; repository test not run |
| U-02 a malformed reading id must stay read-only | `earlierReading` flag set from the route | `ViewerSessionTest.malformedReadingIdStaysReadOnly` (JVM) | pass |
| U-03 writes and delete reach the stores from an earlier reading | `ViewerWrites` guard; `delete()` no-op when read-only | `ViewerSessionTest.deleteIsANoOpWhenReadOnly`; `ViewerViewModelTest.mutationsAreNoOpsWhenReadOnly` and `mutationsReachTheStoresWhenWritable` (JVM) | pass; the guard is tested on the JVM because `ViewerViewModel` needs `viewModelScope` |
| U-04 correction-derived origin and unknown origin or status not named | `ocrUserRevisionId` carried through `FactEvidence`; labels and icons for "from your correction", "Unknown origin", "Unknown status" | `FactReadMappingTest.evidenceKeepsTheCorrectionItWasReadFrom`; `FactDisplayTest.aValueReadThroughACorrectionIsExtractedFromIt` (JVM); `FactsScreenTest.originFromCorrectionAndUnrecognisedLabels` (device) | pass. The device test was not observed failing first |
| U-05 facts writes cancelled on leaving the screen; refusal not reported | writes run in `AppContainer.writeScope` through `FactsCommands`; refused or failed write reports `NOT_SAVED` | `FactsViewModelTest.refusedReviewReportsMessage`, `writeCompletesAfterClear` (JVM, RED then GREEN) | pass. The brief named `FactsViewModelTest`; the seam is `FactsCommands` because `FactsViewModel` needs a ViewModelStore |
| U-06 a correction of another kind, or an unnormalised URL, email or phone, was stored | presenter refuses `KIND_MISMATCH`; `FactWriter` requires a normaliser fixed point for URL, EMAIL and PHONE | `FactsPresenterTest.correctRefusesInputOfAnotherKind`; `FactWriterTest.correctRefusesUnnormalisedUrlForEmailKind` (host SQLite; the brief named `FactWriterHostTest`, the class is `FactWriterTest`) | JVM RED then GREEN |
| Cleanup | `addUserText`, `addUserDate`, `addUserTime`, `addUserMoney` removed (their only callers were tests, now on `addUserInput`); `TooManyFunctions` suppression removed from `FactsPresenter` | existing `FactsPresenterTest` (19 tests) | pass |

Gate: `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test` ended `BUILD SUCCESSFUL in 4m 31s` after one detekt failure was fixed (`edddeae`). `scripts/plan-check.sh`: `steps=83 errors=0`, `runnable now: []`. `bash scripts/tests/run.sh`: `51 passed, 0 failed`.

Device (tablet `U8BBB20806201256`, AGS2-W09, Android 8.0.0), one class per run, `stay_on`, wakeup and dismiss-keyguard before each, XML read after each:

| Class | Result |
| --- | --- |
| FactsFlowTest | 13 pass, 0 fail |
| FactsScreenTest | 2 pass, 0 fail |
| FactsInertValuesTest | 1 pass, 0 fail |
| ViewerScreenTest | 3 pass, 0 fail |
| EvidenceNavigationTest | 8 pass, 0 fail |
| EarlierReadingViewerTest (U-01 run, earlier) | 3 pass, 0 fail |

Totals this review: 27 device tests, 27 passed, 0 failed (plus 3 earlier for EarlierReadingViewerTest).

Gaps:
1. Not run on the tablet in this review: `OcrRepositoryTest` (vault androidTest, compiled only). `ViewerScreenC1Test` was not run in this review (10/0 on 5488566 per the earlier evidence).
2. RED was observed for U-05 and U-06 (JVM). For U-01 to U-04 the RED run is not recorded, so their fixes are not shown to fail first. The instrumented RED for U-01 (`EarlierReadingViewerTest`) and U-04 (`FactsScreenTest`) was not observed on the device.
3. The U-03 guard is covered on the JVM only, not in `ViewerScreenTest` for the read-only UI.
