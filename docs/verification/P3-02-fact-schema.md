# P3-02 Fact model, schema, migration, cascade

Branch `plan/P3-02-fact-schema`, cut from `main` at 31cc2eb. Date
2026-11-26. Implements the storage part of docs/capabilities/C2.md
(§4, §5, §8, §9, §10). Finding F-70: proven on host SQLite; device run
pending.

## Device policy for this run

Owner instruction: no emulator, no `connected*AndroidTest` task and no
`adb` (the attached tablet belongs to another agent). None was started or
run. The instrumented sources were **compiled**
(`./gradlew :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`)
and **not run**. Each test below is **implemented; unverified (gap —
pending physical-device run)**.

| Instrumented test | Command (run on the tablet when it is free) |
| --- | --- |
| `FactRepositoryTest` (`basisEdgeMustPointToAnOlderRevisionOfTheSameFact`, `correctionOnSpanMarksDependentRevisionsStale`) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.FactRepositoryTest` |
| `FactDatabaseSqlCipherTest.versionFiveVaultWithRowsMigratesToTheC2SchemaOnSqlCipher` (v5 to v6 with rows, `MigrationTestHelper`, SQLCipher; from review M-04 it seeds the shipped FTS index with the v5 any-column update triggers and compares every FTS `_data` block before and after the migration) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.FactDatabaseSqlCipherTest` |
| `DeletionRepositoryTest.deletingASourceRemovesFactsEvidenceRunsEdgesAndReviewEvents` | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.DeletionRepositoryTest` |
| `OcrDatabaseTest` (now checks only the `sources_ready%` triggers) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.OcrDatabaseTest` |
| Existing migration and repository tests that now run through v6 (`SourceDatabaseTest`, `OpenLifeDatabaseReadyInvariantTest`, `SearchRepositoryTest`, `OcrRepositoryTest`, `RecoveryRepositoryTest`) | the same command with each class |

The step's verification command `./gradlew :vault:connectedDebugAndroidTest`
and the acceptance criterion "migration on SQLCipher passes with existing
data" stay open until that run.

## What was proven on the JVM

The host tests run the production SQL (`FactSchemaTriggers`,
`FactMigration`, `FactSql`, `SourceRowRemoval`, `OcrPointerSql`) on host
SQLite through `HostSqlite` (python3 `sqlite3`, SQLite 3.53, with
`foreign_keys` on and `recursive_triggers` on by default). **Host SQLite
is not SQLCipher.** No production code sets `recursive_triggers`; the
pinned `libsqlcipher.so` lists `DEFAULT_RECURSIVE_TRIGGERS` among its
compile-option strings (a static read of the AAR, not observed on a
device). The suites whose result could depend on it (`FactGuardTest`'s
delete guards and `SourceRowRemovalTest`'s removal) run with it on and
off (review M-05). The schema DDL is taken from the exported
`vault/schemas/.../6.json`, so the host database has the same tables and
indices Room creates.

| Test class | What it shows |
| --- | --- |
| `FactDatabaseTest` (5) | v5 to v6 migration keeps every v5 row and the FTS data byte for byte, and gives the same shape as a fresh v6 database; every C2 CHECK trigger refuses with its requirement id; the C2-R20 pointer guards; the FTS update triggers are narrowed before any backfill `UPDATE` (C2-R21, C2-R64) |
| `DependencyEdgeTest` (4) | cross-Source edges refused (C2-R18); `BASIS` only to an older non-USER revision of the same fact; the `EVIDENCE` edge goes when the last evidence row naming the span goes; no Kotlin statement writes `dependency_edges` |
| `SourceRowRemovalTest` (2) | F-70: one routine, whole-set order of C2-R43, the same final database (no row of the Source left) for every combination of `foreign_keys` on/off, `recursive_triggers` on/off and a fresh or a migrated v6 database; refuses a non-DELETING Source before running any statement; the pre-C2 order is refused |
| `SourceRowRemovalBoundaryTest` (2) | only the routine and the STAGED cleanup remove a Source row; no other code in `vault` or `app` deletes a fact or OCR row (`DELETE FROM` or `@Delete`); the dead `OcrDao.deleteForSource` was removed (M-09) |
| `FactGuardTest` (5) | review M-01: `facts`, `fact_evidence` and `review_events` rows are deleted only on a DELETING Source (recursive triggers on and off); M-02: a region with any NULL column is refused (C2-R8); M-03: VALUE offsets set and `start < end`; M-07: a DELETING Source's facts are not listed; fresh and migrated v6 have identical trigger and index rows in `sqlite_master` |
| `OcrRevisionPointerTest` (1) | `sources.currentOcrRevisionId` moves only forward to a later READY reading of the Source (C2-R14, F-69 for facts); corrections supersede the chain head explicitly (C2-R15) |
| `OcrSpanStatusTest` (2) | `hasEvidenceRegion` and `scriptStatus` backfilled, eligibility per C2 §1 (Z-16); a classifier failure rolls the whole migration back (C2-R17) |
| `FactWriterTest` (7), `FactWriteFailureTest` (1), `EvidenceOffsetsTest` (1) | review M-08: candidate texts that do not line up one to one are refused; user values must have their kind's shape (DATE, TIME, DATETIME fully; other kinds by leading key, rest in P3-03/P3-05); M-06: a storage failure is `FactWriteResult.Failed`, cancellation rethrown; M-03: the UTF-16 upper bound of the offsets. Plus, from the first round: |
| `FactWriterTest` (first round) | the bodies of `FactRepository`: the §5 status table, clarify byte for byte and re-choose (C2-R36), correct refused on STALE (C2-R37), user facts (C2-R38) and the 500 cap (C2-R62), the BFS stale traversal with BASIS_STALE and the epoch bump on every call (C2-R19, C2-R63) |
| `SchemaV5MigrationTest`, `EnumDecodingTest` | v5 tests updated for the narrowed triggers; tolerant decoding of the new enums |

## RED and mutations

- RED (commit 5c328bf): 22 of 30 targeted tests failed, each for the
  intended reason (no 6.json, TODO stubs, boundary checks, narrowed
  triggers missing).
- `DependencyEdgeTest.noKotlinStatementWritesEdges` passed at RED (no
  Kotlin wrote edges yet), so RED is proven by mutation: appending
  `// MUTATION: DELETE FROM dependency_edges` to `FactDao.kt` and running
  `./gradlew :vault:testDebugUnitTest --rerun --tests 'org.openlife.vault.storage.DependencyEdgeTest'`
  gave `noKotlinStatementWritesEdges FAILED` (4 tests, 1 failed); reverted,
  the same command passes. `--rerun` is needed: a comment-only change
  leaves the test task UP-TO-DATE because these boundary tests read
  sources Gradle does not track as inputs.
- Observation, not a recorded mutation run: on host SQLite an
  `UPDATE OF col` trigger fires even when the value is unchanged, so the
  pointer triggers are guarded by `NEW.x IS NOT OLD.x`.

## Review round (2026-11-26, findings M-01..M-09)

- RED commit f4a0c1a: 10 targeted tests failed, each for its finding
  (missing guards, NULL region accepted, NULL or empty VALUE offsets
  accepted, DELETING facts listed, guards absent from `sqlite_master`,
  `OcrDao.kt: DELETE FROM ocr_revisions` found by the boundary scan,
  misaligned candidate text accepted, wrong-kind user values accepted,
  `TODO` stubs for `captureWriteFailure` and `EvidenceOffsets.fit`).
- The earlier gate ran on 8acdb50, before the merge of `main` (P1-20
  navigation rebuild, 7dd52e1). The gate was re-run on the merged head
  with the review fixes: `./gradlew detekt lint :app:test :vault:test
  assembleDebug :vault:compileDebugAndroidTestKotlin
  :app:compileDebugAndroidTestKotlin :eval:test --continue` -> BUILD
  SUCCESSFUL (exit 0); JVM tests vault 224, app 189, eval 26, 0 failures.
  Two earlier attempts ended "Gradle build daemon disappeared" while
  another project's build shared the 8 GB host; one also reported detekt
  formatting findings, fixed before the passing run.
  `bash scripts/plan-check.sh` -> steps=82 errors=0;
  `bash scripts/tests/run.sh` -> 51 passed, 0 failed.
- The two `FactRepository` stale entry points still throw on a storage
  failure (after their transaction rolled back); only the four user
  writes return `Failed`. P3-04, their caller, decides.

## Gate log note (first round)

The final gate run (`BUILD SUCCESSFUL`, exit 0) log also contains a
"Gradle build daemon disappeared unexpectedly" dump from an earlier,
concurrent client whose daemon died; that client's tasks were re-run by
the successful build. `:vault:lint` was re-run alone afterwards: "No
issues found".

## Deviations recorded in the step notes

See `plan/steps/P3-02.yaml` notes and C2.md §19 (D-23..D-26).

## Fix-round verification (2026-11-26)

An independent reviewer re-ran the production trigger SQL on host SQLite (not
SQLCipher) after the M-01..M-09 fixes and accepted the step for merge. Nothing
in this section has run on a device.

Recorded from that pass:

- **MM-01 (low, open).** `SourceRowRemovalBoundaryTest` also reads `../app`
  sources, which Gradle does not track as inputs of `:vault:test`; after an
  `app`-only change the test can be skipped as up to date. Run it with
  `--rerun` until the source directories are declared as test inputs.
- **MM-02 (note for future migrations).** With foreign keys on,
  `DROP TABLE sources` is refused: the drop cascades and fires the delete
  guards. A migration that rebuilds `sources` must drop the guard triggers
  first and recreate them afterwards.
- User-entered MONEY, URL, EMAIL and PHONE values are checked by prefix shape
  only; full validation is assigned to P3-05.

Added to the on-device checks:

- Read `PRAGMA recursive_triggers` on an opened vault in an instrumented test
  and record the value (the host suites run both modes; the device value has
  not been observed).
- On SQLCipher, deleting a fact, an evidence row or a review event of a READY
  Source is refused.
- Read `SELECT sqlite_version()` on an opened vault in an instrumented test
  and record it. The 3.53.4 below is a static read of the AAR; the device
  value has not been observed.

## CI failure on PR #32 and SQLite versions (2026-11-26)

Hosted CI run 45899006333 failed
`SourceRowRemovalTest.deletionAndRecoveryShareOneRoutineWithTheSameResultWithForeignKeysOnAndOff`
with `HostSqlite$Failure`. Line 51 in the short trace is the test method's
`runBlocking` frame, not the failing statement.

**Reproduced locally.** `OPENLIFE_HOST_SQLITE` (new) lets HostSqlite use another
command that speaks its line protocol. A scratchpad Java harness over the
cached `org.xerial:sqlite-jdbc:3.41.2.2` gives SQLite 3.41.2, which has FTS5
but not secure-delete. On 3.41.2 the test failed with:
`no such table: ocr_text_fts_docsize [statement: SELECT COUNT; host SQLite 3.41.2]`
at `SourceRowRemovalTest.kt:83`, the fixture's FTS row count.

**Root cause: test fixture, not production.**
- On an engine without FTS5 secure-delete (added in 3.42), `SearchIndex`
  fails closed by design and creates no FTS tables.
- The removal routine succeeded. Only the fixture's unconditional count of
  `ocr_text_fts_docsize` failed.
- The same condition already skips 13 tests on CI: 12 in
  `SearchIndexHostTest` and `FactDatabaseTest.ftsUpdateTriggersAreNarrowedBeforeBackfill`.
  Each is skipped through `SearchFixture.assumeIndexed`, whose message names
  the host version.

**Fix.** The count now runs only when the index exists. The index may be
missing only on a host SQLite older than 3.42; on a newer engine a missing
index still fails the test.

**Results after the fix.** The whole vault JVM suite passes on SQLite 3.41.2,
with the same 13 skips as CI, and on 3.53.3.

| Engine | SQLite | How known |
|---|---|---|
| Local host (`python3` `sqlite3`) | 3.53.3 | Observed (`sqlite3.sqlite_version`) |
| Local reproduction (`sqlite-jdbc` 3.41.2.2) | 3.41.2 | Observed (`sqlite_version()`) |
| CI runner (ubuntu-22.04 system `python3`) | 3.37.2 expected (Ubuntu 22.04 `libsqlite3` package) | Not observed in run 45899006333; the next run prints it (`HostSqlite: host SQLite ...` on stderr, and in each skip message) |
| Device (sqlcipher-android 4.19.0 `libsqlcipher.so`, arm64-v8a and x86_64) | 3.53.4 (source id 2026-07-24 19:02:57 bf7c7f30...) | Static read of the AAR, not observed |

**Production SQL check.**
- Production SQL uses nothing newer than SQLite 3.37.2 except FTS5
  secure-delete (3.42). That is in `SearchIndex` only, behind a probe on a
  temp table that fails closed.
- `SqliteVersionFloorTest` covers `FactSchemaTriggers`, `FactMigration`,
  `SourceRowRemoval`, `SearchIndex`, `OcrPointerSql` and the three DAOs. It
  asserts two things:
  - every listed construct is no newer than `BUNDLED_SQLITE` = 3.53.4;
  - every construct newer than `CI_HOST_SQLITE` = 3.37.2 is in the
    fail-closed allowlist.
- RED by mutation: see the step evidence.

**Diagnostics.**
- `HostSqlite.Failure` now carries the SQLite error text, the statement kind
  (leading keywords and object name, no values) and the host version.
- The `:vault` test tasks print failed tests with full exceptions and causes,
  skipped tests, and standard error.
