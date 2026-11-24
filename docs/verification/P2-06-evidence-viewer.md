# P2-06 Evidence viewer UX: PR #23 review fixes (R8-R18)

Source: `docs/reviews/2026-11-24-P2-06-pr23-review.md` (E-01..E-10, review of
head 79926a6). Branch `cursor/p2-06-evidence-viewer-1e36`. Date 2026-11-24.

## Device policy for this run

Owner instruction (2026-11-24): no emulator (locally or in CI) and no use of
the attached tablet, which another agent is using. No emulator was started and
no `connected*AndroidTest` task or `adb` command was run. Therefore:

- Every instrumented test named below was **compiled**
  (`./gradlew :app:compileDebugAndroidTestKotlin :vault:compileDebugAndroidTestKotlin`
  => BUILD SUCCESSFUL) but **not run**. Its status is
  **gap — pending physical-device run**.
- Logic was moved behind pure seams where possible (`TapGesture`,
  `DoubleTapClock`, `EvidenceGeometry.pixelBox`, `EvidenceSelection`,
  `OcrSpanReviewRules`, `OcrEvidenceRegion.withinSource`, `ocrViewOf`) and JVM
  tests prove them. Those JVM results are the evidence cited here.
- Robolectric was not added.

## Commits

| Phase | Commit | Content |
|---|---|---|
| merge | 0ec0456 | P1-18 vault remediation merged into this branch (v3 migration re-asserts the READY triggers) |
| RED | b375703 | failing JVM tests, instrumented tests (compiled only), `TODO()` stubs |
| GREEN | caea392 | fixes for E-01, E-02, E-05, E-06, E-08, E-09, E-10 |
| REFACTOR | ee80345 | detekt fixes only, no behaviour change |

The image's own selectable filter (`EvidenceContent.selectable`) repeats the
rule in `EvidenceSelection.selectableSpans` (grounded and not rejected). The
JVM test covers only the latter. Folding them together is left for P1-20,
which rebuilds the viewer.

RED, from the real Gradle output at b375703:

- vault JVM: 115 tests, 9 failed. NotImplementedError: the 4
  `OcrSpanReviewRulesTest` tests and the region-bounds test.
  AssertionError: `OcrViewOfTest.latestSpanReviewIsChosenBySequenceNotClock`
  (expected UNREVIEWED, was REJECTED), `SchemaV3MigrationTest` createSql
  (the export has `sequence`, the migration did not) and byte-identical
  (lengths 14005 vs 13751). IllegalArgumentException:
  `EnumDecodingTest.unknownSpanReviewStateDecodesToFallback`
  ("No enum constant ... DISPUTED").
- app JVM: 86 tests, 13 failed, all new: `EvidenceGesturesTest` (8),
  `EvidenceGeometryTest.zoomedImageIsMeasuredAtItsZoomedSizeNotTheCanvas`,
  `EvidenceSelectionTest`, `OcrViewModelTest.rejectAndUndoRejectReachTheBackend`,
  `OcrStateMapperTest.readyRevisionWithAnUnrecognisedSpanReviewIsUnreadable`
  (expected UNRECOGNISED, was null),
  `SourceEgressBoundaryTest.evidenceBoundsLogRequiresDebugGuard`
  ("an EvidenceBounds log outside BuildConfig.DEBUG must be a violation").
- Instrumented RED: **never observed.** No instrumented test added by this
  revision has been run, failing or passing, because no device was allowed.
  This is a deviation from R10 and AGENTS.md (see below).

GREEN and after: app JVM 86/86, vault JVM 115/115 (debug unit tests; counts
from the JUnit XML).

### Recorded mutations (tests that passed on first run)

Each mutation was applied and the named test was run. The file was then
restored from a backup of the working tree, and the restored file matched
the RED baseline. The TRANSPOSE/TRANSVERSE swap and the `clampPan /1`
mutation were applied together in one run. Each failure is still
attributable: the clamp test uses NORMAL only, and the oracle does not call
`clampPan`. The other mutations were run one at a time.

| Test | Mutation | Result |
|---|---|---|
| `EvidenceGeometryTest.allEightOrientationsMatchHandComputedLetterboxedRects` | swap the TRANSPOSE and TRANSVERSE branches of `displayPoint` | failed: "TRANSPOSE left expected:<120.0> but was:<240.0>" |
| `EvidenceGeometryTest.clampPanPanToRevealSmallestSpanAtAndNextDoubleTapZoom` | `clampPan` divides by 1 instead of 2 | failed: expected Offset(100.0, -100.0), was Offset(200.0, -100.0) |
| same | `nextDoubleTapZoom` uses `<=` at the midpoint | failed: expected 1.0, was 3.0 |
| same | `smallestSpanAt` uses `maxWithOrNull` | failed: expected the small span, got the wide one |
| same | `panToReveal` always pans (`if (true)`) | failed: expected Offset(-100.0, -100.0), was Offset(0.0, 0.0) |
| `SchemaV3MigrationTest.migrationTwoToThreeEndsByReassertingTheReadyTriggers` | remove `ReadyInvariantTriggers.create(db)` from `MIGRATION_2_3` | failed: expected "DROP TRIGGER IF EXISTS sources_ready_requires_fields_INSERT", was the CREATE TABLE |
| `SourceEgressBoundaryTest.evidenceBoundsLogRequiresDebugGuard` | built in: the test removes the `if (BuildConfig.DEBUG) {` line and requires a violation | failed at RED (rule missing), passes at GREEN |

## Outcome per finding

| Finding | Requirement | Outcome |
|---|---|---|
| E-01 zoomed image measured at canvas size | R8 | **Implemented, unverified (gap — pending physical-device run).** Only the geometry arithmetic is proven on the JVM; that arithmetic was already right before the fix, so the JVM test does not prove E-01 is fixed. The image and each region use a `placeAt(IntRect)` layout modifier. It measures the node with `Constraints.fixed` at its zoomed pixel box from `EvidenceGeometry`, places it at the box origin, and reports the canvas size, so Compose does not re-centre it. `EvidenceGeometry.pixelBox` and `zoomedImageIsMeasuredAtItsZoomedSizeNotTheCanvas` check the 3x box (-100,-100,200,200) on a 100 px canvas and a region inside it (JVM). Nothing on the JVM runs `placeAt` or Compose measurement. |
| E-02 tap after drag/pinch; detector restarts | R9 | **Tap-classification logic proven on the JVM; the pointer-input adapter and pinch are implemented, unverified (gap — pending physical-device run).** `TapGesture` abandons the tap past touch slop, on a second pointer, or when any change is consumed in the main or the final pass. `DoubleTapClock` keeps double-tap timing across pans. `pointerInput(Unit)` with `rememberUpdatedState` reads zoom, pan and spans when the tap ends. JVM: `EvidenceGesturesTest` (8 at the first review, plus S-04 and S-06). The JVM tests do not run `awaitTapRelease`, `pointerInput` or `transformable`; pinch has no test that has run. **Deviation:** R9 names `waitForUpOrCancellation()`. The adapter `awaitTapRelease` mirrors its main- and final-pass consumption checks but delegates to `TapGesture`, because `waitForUpOrCancellation()` has no slop or second-pointer rule and cannot be tested on the JVM. |
| E-03 overclaiming docs | R10 | **Fixed in docs.** C1-R4 no longer claims pinch zoom and pan as proven. The plan evidence no longer lists "scale 1-8x, double-tap 1x/3x" without test names. The old hosted runs are kept only as history of their commits. **Deviation:** the instrumented RED was not shown failing (no device). Alignment at >1x is proven on the JVM only; on a device it is a gap. |
| E-04 no SQLCipher upgrade to v3 | R11 | **Test written, not run.** `OcrDatabaseTest.sqlcipherMigrationToCurrentVersionKeepsRowsAndReadyTriggers` and `migrationAddsSpanReviewsAndCascades` now use the SQLCipher helper and the production factory with `readyInvariantCallback`. They validate at the `@Database` version read from the opened database. On the JVM, `SchemaV3MigrationTest` proves the migration DDL equals the export and ends with the triggers. |
| E-05 latest review by clock | R12 | **Fixed.** `ocr_span_reviews.sequence INTEGER NOT NULL` with a unique `(spanId, sequence)` index. The DAO assigns `MAX(sequence)+1` per span in the INSERT. `ocrViewOf` and `OcrSpanReviewRules.latest` choose by sequence. v3 was unreleased (no release or tag carries it), so `MIGRATION_2_3` and both 3.json copies were amended in place. The copies are byte-identical (`cmp`, `bothCopiesOfTheV3SchemaAreByteIdentical`). An unknown stored state decodes to UNRECOGNISED and makes the revision unreadable instead of crashing. |
| E-06 no undo, double reject | R13 | **Rules proven on the JVM; UI implemented, unverified (gap).** A rejected span shows "Undo reject" instead of "Reject span". Undo appends the previous state, and rows are never updated. Reject is refused on a rejected span or a non-READY revision. A rejected region is drawn dashed in muted ink, has no click action, is skipped by hit-testing, and drops the current selection. JVM: `OcrSpanReviewRulesTest`, `EvidenceSelectionTest`, `OcrViewModelTest`. UI: gap. |
| E-07 test gaps | R14 | **Partly closed.** The independent eight-orientation oracle with letterboxing on both axes and the helper tests are JVM tests, mutation-proven. The repository, UI and pinch tests are written but have never run (gap — pending physical-device run). |
| E-08 bounds log guard | R15 | **Fixed.** The allowlist now requires the log line to sit inside an `if (BuildConfig.DEBUG) {` block, found by a character-accurate backwards scan (S-03). The guard's `else` branch is rejected. Proven by two built-in mutations: the guard removed, and the log moved into the `else` branch. |
| E-09 narrow screens (suspected) | R16 | **Not confirmed on a device; fixed by layout.** By arithmetic, three TextButtons need about 282 dp of a 328 dp row at 360 dp, so the text would get about 46 dp. The span text now takes the full width and the actions wrap in a `FlowRow` below it. `spanRowKeepsUsableTextWidthAtNarrowWidthAndLargeFont` is written but not run. **Deviation:** R16 says confirm first; the fix was applied without confirmation. |
| E-10 region past the image edge (suspected) | R17 | **Rule proven on the JVM; persistence path unverified (gap).** At persistence a region whose right or bottom exceeds the source size is stored ungrounded (null region), never clamped. Both engines map regions to raw source pixels (`OcrCoordinateMapper.toSourcePixels`) before persist, so the check compares like with like on rotated sources. JVM: `OcrSpanValidationTest`. On a device: gap (`regionBeyondSourceBoundsIsNotPersistedAsGrounded`). |

No finding was refuted by a test.

## Instrumented tests awaiting a device run

**First, uninstall any earlier build of this branch.** The unreleased v3
schema identity changed, so a vault left by an earlier build fails Room's
identity check. Do not reset the vault to work around it.

All are **gap — pending physical-device run**. Run one class with:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.EvidenceNavigationTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.ViewerScreenC1Test
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.OcrDatabaseTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.OcrRepositoryTest
```

| Class | New or changed methods |
|---|---|
| `org.openlife.app.ui.EvidenceNavigationTest` | `doubleTapZoomsImageToThreeTimesItsFitWidth`, `tapAtThreeTimesSelectsTheSpanUnderTheFinger`, `verticalScrollStartingOnTheImageSelectsNothing`, `pinchReleaseDoesNotSelect`, `doubleTapAtThreeTimesZoomsOutEvenWhenTheFirstTapPans`, `rejectedSpanRegionCannotBeSelectedAsEvidence` |
| `org.openlife.app.ui.ViewerScreenC1Test` | `rejectedSpanOffersUndoAndCannotBeRejectedTwice`, `spanRowKeepsUsableTextWidthAtNarrowWidthAndLargeFont` |
| `org.openlife.vault.storage.OcrDatabaseTest` | `sqlcipherMigrationToCurrentVersionKeepsRowsAndReadyTriggers`, `latestSpanReviewIsChosenBySequenceNotClock`; changed: `migrationAddsSpanReviewsAndCascades` (SQLCipher, current version), `migrationOnSqlCipherPreservesReadyInvariantTriggers` (current version), `deletingSourceCascadesAllOcrRows` (appends through the DAO) |
| `org.openlife.vault.repository.OcrRepositoryTest` | `rejectSpanKeepsTextRequiresReadyAndIsObservedThroughTheFlow`, `regionBeyondSourceBoundsIsNotPersistedAsGrounded` |

The manual tall/wide transcript (step verification) is also pending a device.

### Device checklist (second review, in priority order)

1. Uninstall, then do a fresh install.
2. At 3x with no pan, and at each pan extreme: the image covers the canvas,
   the overlays line up, and a tap selects the span under the finger.
3. At 1x, a vertical swipe that starts on the image scrolls the column and
   selects nothing.
4. Pinch, then lift one finger: nothing is selected.
5. A tap that stops a fling selects nothing (S-06). The adapter abandons the
   tap if the down's final pass is consumed. This is implemented, and it is
   unverified that the column's scroll consumes that down.
6. With the image half visible, double-tap on a region.
7. Reject, Undo, Reject: one row per press, sequences 1, 2, 3. Nothing is
   selectable while rejected. No scroll jump: rejecting the selected span
   clears the selection, so Undo does not re-reveal it (S-02, not testable
   on the JVM because the selection lives in `ViewerScreen`'s `remember`).
8. Run `OcrDatabaseTest` and `EvidenceNavigationTest`.

## Second review round (S-01..S-06)

- S-01: the claims above are corrected. The instrumented RED was never
  observed.
- S-02: `ViewerScreen` clears `selectedSpanId` when the selected span is
  rejected (device checklist item 7).
- S-03: the DEBUG-guard scan walks backwards one character at a time and
  accepts only a block whose own header is `if (BuildConfig.DEBUG)`. The
  guard's `else` block (header `} else`) is rejected. A second built-in
  mutation (`} else {` inserted after the guard) must be reported.
- S-04: `DoubleTapClock` also needs the second tap within 100 dp of the
  first (Android's double-tap slop). JVM:
  `EvidenceGesturesTest.twoQuickTapsFarApartAreTwoSingleTapsNotAZoom`.
- S-05: uninstall instruction added above.
- S-06: the adapter checks the down's final pass through
  `TapGesture.onFinal`. The JVM test
  (`aDownConsumedByAParentIsNotATap`) covers the rule, not the adapter.
- Minor fixes:
  - `sqlcipherMigrationToCurrentVersionKeepsRowsAndReadyTriggers` no longer
    creates the triggers at v2, so finding them proves MIGRATION_2_3
    re-creates them.
  - `migrationAddsSpanReviewsAndCascades` passes the full `MIGRATIONS` list.
  - The OcrPanel comment now cites R16.
- None of this round was compiled or run locally: no Gradle run was allowed
  on the shared host. Hosted CI (build, lint, JVM tests) is the check.

## Existing tests changed

- `OcrDatabaseTest.deletingSourceCascadesAllOcrRows`: inserts the span review
  through `OcrSpanReviewDao.append`, because the raw `insert` was removed.
- `OcrDatabaseTest.readyInvariantTriggersExistAfterMigratingToCurrentVersion`
  (in the merge commit): migrates to v3, because "current" is now 3.
- `OcrDatabaseTest.migrationAddsSpanReviewsAndCascades`: moved to the
  SQLCipher helper and the current version (R11). It is no weaker: it inserts
  a `sequence` and reopens through the production factory.
- `OcrViewModelTest.spanReviewFromThePersistedViewIsRejected`: passes a
  `sequence` to the `OcrSpanReview` constructor.

## Known limits

- The rejected-region style (dashed outline in `mutedInk`) was not reviewed
  against `docs/design/VISUAL_IDENTITY.md` or the M3 skills in this session.

- `Constraints.fixed` packs both sides into one value. When both sides are
  large, each is capped at about 32767 px. At the maximum zoom (8x), a
  2560 px canvas needs about 20.5k px per side, which is inside that cap. A
  larger canvas or zoom would overflow it. This is reasoned from the Compose
  `Constraints` encoding, not measured.
- Zoom still scales the sampled preview (at most 4M px); there is no full-size
  re-decode in v0.2.0 (R7).
