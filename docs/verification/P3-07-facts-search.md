# P3-07 verification: facts in local search

Step: P3-07 "Facts in local search". Branch `plan/P3-07-facts-search`.
Requirements: P3-07-R1 (fact_fts index and query-time filter), P3-07-R2 (result
kind and status; tap opens the viewer with the evidence span selected).

## Schema change (flagged)

- **Schema v7.** `OpenLifeDatabase` moves from version 6 to version 7 (`Vault.SCHEMA_VERSION = 7`).
  The new schema JSON is `vault/schemas/org.openlife.vault.storage.OpenLifeDatabase/7.json`.
- **New objects.** The virtual table `fact_fts` (FTS5, external content over
  `fact_revisions`, `content_rowid = rowid`, `tokenize = unicode61 remove_diacritics 2`,
  `secure-delete` on), the triggers `fact_fts_ai` (insert) and `fact_fts_ad` (delete,
  `'delete'` form), and the shadow tables `fact_fts_data`, `_idx`, `_docsize`, `_config`.
- **No update trigger.** Only insert and delete triggers exist. The indexed columns are immutable (C2-R4).
- **Migration 6 to 7.** `MIGRATION_6_7` runs `FactSchemaTriggers.create` and then
  `FactSearchIndex.create`. The `'rebuild'` statement in `FactSearchIndex.statements()`
  **backfills existing revisions**. The SQLCipher migration test confirms this: a v6 vault
  with one fact revision migrates to v7, and `MATCH '2026'` returns that pre-existing revision.
- **Pointer ordering (F-71).** `c2_sources_current_reading` (the trigger), `MOVE_CURRENT_READING`
  and the viewer now order readings by `rowid`. The shipped v6 backfill
  (`FactMigration.BACKFILL_CURRENT_READING`, `startedAt DESC, rowid DESC`) is **not edited**.

## Rules quoted

C2-R49 (`docs/capabilities/C2.md`, lines 1129 to 1140, abridged):

> Facts are indexed by an FTS5 external-content table over `fact_revisions(canonicalText, rawText)` ...
> Only insert and delete triggers. Every revision is indexed. The indexed columns are immutable (C2-R4), so no update trigger is needed. Status changes never touch the index (ZZ-09).
> Which hits: filtered at query time. A hit is returned only when its revision meets all of these, checked by a join on each query: it is its fact's current revision (`facts.currentRevisionId`); its status is `UNREVIEWED`, `CONFIRMED` or `CLARIFICATION_NEEDED`; its fact's Source is `READY`; ...

C2-R42 (line 996): "Stale revisions are excluded from search (C2-R49)."

C2-13 (line 1298) lists the excluded states that must be tested: one fact in each status, superseded, STALE, `UNRECOGNISED`, non-current reading, non-READY Source.

C2-R14 (line 373 onward), moving-pointer text:

> The transaction that commits a reading `READY` (C1-R6) moves the pointer to it only if no current reading exists, or if the new reading's `(startedAt, rowid)` is greater than the current ...

The implementation orders by `rowid` only (F-71). See "Open questions".

## Excluded states: test coverage

| Excluded state | Test | Result |
|---|---|---|
| REJECTED fact (text stays indexed) | `FactSearchHostTest.rejectedFactIsNotShownButItsTextStaysIndexedForQueryTimeFiltering` (host) | passes |
| Superseded revision (fact's current pointer moved to a newer revision) | `FactSearchHostTest.supersededRevisionIsNotShownButItsTextStaysIndexedForQueryTimeFiltering` (host) | passes |
| STALE fact status | `SearchRepositoryTest.findsConfirmedAndUnreviewedFactsButNotRejectedOrStale` (device) | 9/9 class run |
| Non-current reading (EXTRACTED fact on an older reading) | `SearchRepositoryTest.findsConfirmedAndUnreviewedFactsButNotRejectedOrStale` (device) | 9/9 class run |
| Deleted Source (no text left; secure-delete) | `FactSearchHostTest.deletedSourceLeavesNoFactTextAndTheIndexStaysIntact` (host) | passes |
| `UNRECOGNISED` (C2-R51 decode fallback; no stored row can hold it) | `FactSearchHostTest.unrecognisedStatusIsRejectedByTheSchemaSoNoSuchRevisionIsSearchable` (host) | passes: the schema rejects the status |

`UNRECOGNISED` is the tolerant-decoding fallback of C2-R51 (`FactWriter`, `FactMappers`), not a stored value. C2-R49 excludes it from hits. No stored row can hold it, because the C2 triggers in `vault/src/main/java/org/openlife/vault/storage/FactSchemaTriggers.kt` reject unknown values:
- fact kind: `c2_facts_insert`, line 46 (`NEW.kind NOT IN $KINDS`), on insert;
- revision origin: `c2_fact_revisions_insert`, line 67 (`NEW.origin NOT IN ('EXTRACTED', 'USER')`);
- revision status: `c2_fact_revisions_insert`, line 71, and `c2_fact_revisions_status`, line 108 (`NEW.status NOT IN $STATUSES`), on insert and on update.

The host test sets the status to `UNRECOGNISED` and expects the schema to abort the update. `SHOWN_FACT` checks status only, so it would not filter an unknown kind or origin; that case is also unreachable, for the reason above.

The host test for superseded revisions needed a helper change: `FactHost.revision` now
takes `rawText`, because `fact_fts` also indexes `rawText`, and the fixture's default
`26.11.2026` would otherwise match the query.

## Results

Device (physical tablet, serial U8BBB20806201256; no emulator):

| Class | Result | Evidence |
|---|---|---|
| `FactSearchMigrationSqlCipherTest` (migration 6 to 7 and fresh v7) | 2/2 | recorded run |
| `SearchRepositoryTest` (SQLCipher search cases) | 9/9 | recorded run |
| `SearchScreenTest` (`factHitShowsKindAndStatusAndOpensTheViewerWithTheSpanSelected` and two others) | 3/3 | `app/build/outputs/androidTest-results` XML, 02:42:23 |
| `FtsAvailabilityTest` (`org.openlife.vault.storage`) | 2/2 | `vault/build/outputs/androidTest-results` XML, 02:43:48 |

The 2/2 and 9/9 totals come from the run records of this step; the XML for those two
classes was overwritten by later runs and was not re-read in this pass.

Host (JVM):

- `:vault:test`: 305 tests, 0 failures, 0 errors (`FactSearchHostTest` 6/6, `SearchIndexHostTest` included).
- `./gradlew --offline detekt` on HEAD: `BUILD SUCCESSFUL`, exit 0 (`:vault` test sources changed after the gate).
- `:app:test`: 241 tests, 0 failures. `:eval:test`: 26 tests, 0 failures.
- Gate `./gradlew --offline detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`: `BUILD SUCCESSFUL`, exit 0 (`scratchpad/gate4.log`). The gate predates three test-only commits (superseded test, schema-rejection test, helper change). Those were checked by `:vault:test` (305, green) and `detekt` (green) on HEAD. `:app` and `:eval` code has not changed since the gate.

## Behaviour delivered

- Search hits (`SearchHit.fact`) carry `factRevisionId`, `kind`, `status` and `origin`.
- The result row shows kind, status and origin ("Date", "Needs clarification", "Extracted from text" and so on).
- Tapping a fact hit opens the viewer with the evidence span selected. The Facts panel is
  P3-05 and does not exist yet, so the viewer span path is used (accepted until P3-05).
  The test was renamed to match: `factHitShowsKindAndStatusAndOpensTheViewerWithTheSpanSelected`.

## Device-verification gaps

- The SQLCipher device tests cover STALE and non-current reading. Superseded and REJECTED are covered on the host only.
- The Facts panel does not exist; tap-to-viewer is the interim path (P3-05).
- The first device attempts failed for environment reasons, not code: an install conflict after
  a timed-out run, and the tablet on the lock screen (`mDreamingLockscreen=true`). The lock
  screen was dismissed with `adb shell wm dismiss-keyguard`; no credential was changed.
- The tablet's `stay_on_while_plugged_in` was raised to 3 for the runs and restored to 0.

## Open questions (for the owner)

1. **Index every revision versus the lead's "remove on stale" brief.** C2-R49 and ZZ-09 govern:
   every revision is indexed and filtered at query time. Confirmed by the coordinator.
2. **Viewer versus search divergence (for P3-05).** The viewer shows the latest revision in any
   state (P2-01-R1). Search shows the READY pointer reading. For a STALE latest reading,
   search shows the older READY reading. Not changed in P3-07, per the coordinator.
3. **F-71 and C2-R14 wording.** Amended 2026-11-28 (S-01, `docs/capabilities/C2.md`): the
   current reading is the greatest `rowid`; `startedAt` is display data only. The shipped v6
   backfill still orders by `startedAt` (known limit, S-02); the migration is not edited.
4. **C2-13 wording.** C2-13 lists `UNRECOGNISED` as a state to test. The schema triggers make it unstorable, so the test proves the trigger rejects it. The owner may reword C2-13 to say so.

## Schema review S-01..S-06 (2026-11-28)

Branch `plan/P3-07-facts-search`. Each item has its own commit (prefix `P3-07:`).

| Item | Change | Test or check |
|---|---|---|
| S-01 | C2-R14 dated amendment: current reading is the greatest `rowid`; F-71 row resolved; plan.yaml F-71 text appended | Commit `a4ad4b7`; doc only |
| S-02 | Known limit recorded: v6 backfill (`FactMigration.BACKFILL_CURRENT_READING`) still orders by `startedAt`; not edited | Recorded in the S-01 amendment |
| S-03 | `OcrDao.findRevisionsForSource` orders by `rowid ASC`; unused `observeRevisionsForSource` removed; KDocs in `OcrPointerSql` and `OcrViewDao` say rowid | Compiles in the gate (`:vault:compileDebugAndroidTestKotlin`); existing OCR tests run in the gate |
| S-04 | `SearchIndex` KDoc: no VACUUM for the FTS rowid mapping and the reading order; new `NoVacuumTest.noProductionSourceRunsVacuum` | Mutation (recorded): a temporary `VACUUM` string in a vault main source fails `NoVacuumTest` and the existing `SearchIndexBoundaryTest.nothingRunsVacuum`; reverted |
| S-04 host test (4) | `OcrRevisionPointerTest.aReadingWithAnEarlierStartedAtStillBecomesCurrentAndIsListedFirst`: clock set back (`startedAt` = 1), the later reading becomes current and `ORDER BY rowid DESC` lists it first | Passes. Mutation (recorded): ordering the pointer by `startedAt` fails this test; reverted |
| S-05 | LIKE fallback matches each term in the canonical or the raw text, as the FTS index does | `FactSearchHostTest.likeFallbackFindsARawTextOnlyMatch`. RED before the fix (recorded), GREEN after |
| S-05 note | An FTS hit that matches only `rawText` shows the canonical snippet, so the match is not highlighted in the snippet. Recorded as a known display limit, not changed in P3-07 | Doc note |
| S-06 | `FactSearchIndex.isComplete` returns false on an engine below the SQLite floor, as `SearchIndex` does | `FactSearchHostTest.ensureOnAnEngineBelowTheFloorDoesNotKeepAnIntactIndex`. RED before the fix (recorded), GREEN after |

Host tests (1) to (4), all in the JVM suite:

1. `FactSearchHostTest.updatingRawTextIsRefusedByTheImmutabilityTrigger`
2. `FactSearchHostTest.factIndexPassesIntegrityCheckWithRankOneAfterSourceRowRemoval`
3. `FactSearchHostTest.createOnAnEngineBelowTheFloorReturnsFalseAndLeavesNoFactIndex`
4. `OcrRevisionPointerTest.aReadingWithAnEarlierStartedAtStillBecomesCurrentAndIsListedFirst`

Gate (one foreground command):
`./gradlew --offline detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`
Result: `BUILD SUCCESSFUL in 2m 39s` (exit 0). JVM tests: vault 312, app 241, eval 26, 0 failed.
An earlier attempt failed when the Gradle daemon disappeared during the run (not a test failure); the rerun passed.

Other checks: `bash scripts/plan-check.sh` gives `steps=83 errors=0`. `bash scripts/tests/run.sh` gives `51 passed, 0 failed`.

Tablet (serial `U8BBB20806201256`, single class per run, `stay_on_while_plugged_in` set to 3 and restored to 0, verified):

- `org.openlife.vault.search.SearchRepositoryTest`: 9 tests, 0 failures, 0 errors.
- `org.openlife.vault.storage.FactSearchMigrationSqlCipherTest`: 2 tests, 0 failures, 0 errors.
