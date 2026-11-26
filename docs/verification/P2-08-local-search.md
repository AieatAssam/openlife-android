# P2-08 Local text search (ADR-0010)

Branch `plan/P2-08-local-search`, based on the head of
`plan/P2-07-assessment-state` (591f168). Date 2026-11-25. Closes F-22.

## Device policy for this run

Owner instruction: no emulator, no `connected*AndroidTest` task, no `adb`
(the attached tablet belongs to another agent). None was started or run.
The instrumented tests below were **compiled**
(`:vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`)
and **not run**. Each one's status is **implemented; unverified (gap —
pending physical-device run)**. No Robolectric and no new dependency were
added.

| Instrumented test | Command (run on the tablet when it is free) |
| --- | --- |
| `FtsAvailabilityTest.sqlcipherBuildSupportsFts5` | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.FtsAvailabilityTest` |
| `SearchRepositoryTest` (5 brief-named tests, `secureDeleteIsOnForBothTables`, `anUpdatedCorrectionLeavesNoOldTermInTheIndex`, `roomValidatesAMigratedDatabaseWithTheFtsTablesPresent`) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.search.SearchRepositoryTest` |
| `SearchScreenTest` (3 tests: compact and expanded routes through `OpenLifeNavHost`, field cleared on stop) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.search.SearchScreenTest` |
| Existing migration tests now run through v5 (`OcrDatabaseTest`, `SourceDatabaseTest`, `OpenLifeDatabaseReadyInvariantTest`) | same command with each class |

The acceptance target of "under 200 ms for 500 spans on the emulator" is
also a device gap.

## Engine evidence (read from the artefact, not run)

See ADR-0010. In summary, `sqlcipher-android` 4.19.0 ships an AAR with
SHA-256 `3f2aeebb…56b41fe`, and that hash matches
`gradle/verification-metadata.xml`. All four ABI `libsqlcipher.so` files
contain the compile options `ENABLE_FTS5`, `ENABLE_FTS3`/`FTS4`,
`HAS_CODEC` and `DEFAULT_RECURSIVE_TRIGGERS`, and the strings `unicode61`,
`remove_diacritics` and `bm25`. The bundled SQLite is 3.53.4.

## JVM evidence on the host engine

`HostSqlite` (vault test sources) runs SQL through `python3 -I` with the
stdlib `sqlite3` module (SQLite 3.53.3, FTS5 present). It turns on
`foreign_keys` (as Room does) and `recursive_triggers`. (Corrected
2026-11-26, P3-02 review M-05: no production code sets
`recursive_triggers`; the pinned `libsqlcipher.so` lists
`DEFAULT_RECURSIVE_TRIGGERS` among its compile options, read statically
from the AAR and not observed on a device. An
earlier version of this record said `recursive_triggers` is what makes the
cascade fire the FTS delete triggers. That was wrong: a host check showed
they fire with it off too.) `SearchIndexHostTest` does
three things:
- builds the v4 schema from `4.json`;
- seeds it and runs the real `MIGRATION_4_5`;
- queries through the real `SearchRepository` with a `SearchDao` that
  executes the generated SQL on the host.

The engine is SQLite, not SQLCipher; the index SQL is the same.

Host latency for 500 spans with 50 hits (limit 50):

- FTS5 (`fiveHundredSpansOnTheHostEngine`): 16 ms in the GREEN run,
  43 ms in the 52ed89a run, and 25 ms in the final run.
- LIKE fallback with no index (`fiveHundredSpansOnTheLikeFallback`, R5):
  25 ms in the final run.

These are host VM figures, not device figures. Each test asserts only that
its time is under 1000 ms.

## Schema JSON diff (4.json -> 5.json)

```
4c4
<     "version": 4,
---
>     "version": 5,
```

The identityHash is unchanged. The FTS tables are not entities, so they
are not in the export. `SchemaV5MigrationTest` checks that both `5.json`
copies are byte-identical.

## RED

RED commit c6a8e5c. `./gradlew :vault:test :app:test
:vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin
--continue` gave these results:
- vault: 177 tests, 28 failed;
- app: 133 tests, 7 failed.

Every failure was intended: `NotImplementedError` stubs, the missing
`5.json`, the missing `4→5` migration, and the empty writer list in the
boundary test. Both androidTest source sets compiled.

Two boundary checks already passed before GREEN. Their RED was therefore
proven by recorded mutations, run after REFACTOR. Each mutation was
reverted, and `git status` was clean for both files afterwards:
- Appended `internal const val MUTATION_PROBE = "DELETE FROM ocr_text_fts"`
  to `DeletionRepository.kt`. This failed
  `deletionRepositoryHasNoFtsStatement` and `onlySearchIndexWritesFtsRows`
  (`expected:<[SearchIndex.kt]> but was:<[DeletionRepository.kt,
  SearchIndex.kt]>`).
- Appended `internal const val VACUUM_PROBE = "VACUUM"` to
  `SearchRepository.kt`. This failed `nothingRunsVacuum`
  (`expected:<[]> but was:<[SearchRepository.kt]>`).

Result: `SearchIndexBoundaryTest`, 3 tests completed, 3 failed.

## VERIFY

Commits:
- GREEN aff869b;
- REFACTOR 52ed89a;
- review fixes 82d3d88. These show the search icon on an empty list too,
  pass `validateDroppedTables = false` in the v5 migration validations,
  and time the LIKE fallback.

The final VERIFY run was on 82d3d88, on this VM, on 2026-11-25:

```
./gradlew :app:cleanTestDebugUnitTest :vault:cleanTestDebugUnitTest detekt lint \
  :app:test :vault:test assembleDebug \
  :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin --continue
=> BUILD SUCCESSFUL in 5m 22s
```

- vault JVM: 177/177 passed, 0 failed, 0 skipped. This includes:
  - `SearchIndexHostTest` 11/11;
  - `SearchQueryTest` 6/6;
  - `SearchSnippetTest` 6/6;
  - `SchemaV5MigrationTest` 5/5;
  - `SchemaV4MigrationTest` 4/4;
  - `SearchIndexBoundaryTest` 3/3;
  - `VaultTest` 1/1 (`SCHEMA_VERSION` equals `@Database` version 5).
- app JVM: 133/133 passed. This includes `SearchPresenterTest` 7/7,
  `NoHardcodedUiStringsTest` 2/2 and `SourceEgressBoundaryTest` 5/5, which
  passes with no new exemption (R9).
- `scripts/plan-check.sh`: steps=82, errors=0.
- `bash scripts/tests/run.sh`: 47 passed, 0 failed.
  - An intermediate run went 46/1. "done-evidence diagnostic names key"
    failed because escaped double quotes in the P2-08 plan entry broke the
    test's `sed 's/, evidence: "[^"]*"//'`.
  - The entry now uses single quotes, and the re-run passed.

Connected tests: none were run, by owner instruction. There was no CI
run, because nothing was pushed.

## Changed existing tests

- `SchemaV4MigrationTest.migrationsAreListedInOrderThroughVersionFour` was
  removed. It asserted that the migration list ends at 3→4.
  `SchemaV5MigrationTest.migrationsAreListedInOrderThroughVersionFive`
  supersedes it.
- `OcrDatabaseTest` (instrumented; three tests that migrate to
  `currentDatabaseVersion()`) and the new
  `SearchRepositoryTest.roomValidatesAMigratedDatabaseWithTheFtsTablesPresent`
  now pass `validateDroppedTables = false` to `runMigrationsAndValidate`.
  - Reason, read from room-testing 2.8.5 (`MigrateOpenDelegate.class`): with
    `true`, Room runs `SELECT name FROM sqlite_master WHERE type = 'table'
    AND name NOT IN (?, ?, ?)`, excluding room_master_table,
    sqlite_sequence and android_metadata. It throws "Unexpected table" for
    any table that is not an entity, which includes the FTS5 tables and
    their shadow tables.
  - Every entity table is still validated against the exported schema.
  - Not run (device gap).
- `SearchIndexHostTest.likeFallbackHasTheSameApiWhenFts5IsAbsent` (a
  P2-08 RED test) changed during GREEN. The RED input `"50_"` cannot test
  literal wildcards, because the sanitiser drops `_` and searches for the
  word "50". The assertion now checks that `"% _ %_"` searches nothing.

No other existing test changed. `ContentTextField`,
`SourceEgressBoundaryTest`, `ButtonShapeSourceTest`, lock files and theme
files are unchanged.

## Review round W-01..W-06 (commit "P2-08: address review findings W-01..W-06")

**Status: not compiled or run locally until CI reports.** Gradle was not
run for this round (another agent held the host's single build slot). The
JVM tests below are written; their results come from the hosted CI run
after the coordinator pushes. Nothing in this section is device evidence.

### Host experiments (python3 -I, sqlite3 3.53.3, run 2026-11-25)

Scripts in the session scratchpad (`exp208/e1.py`..`e5.py`), stdlib only:
- Without `secure-delete`, a deleted term's bytes stayed in `<t>_data`
  (reviewer's result, re-run). With `secure-delete = 1` they were absent
  after a cascade from `sources`, a single-source delete and an in-place
  update of a correction; terms of the other source stayed (positive
  control).
- The option is a `<t>_config` row (`k = 'secure-delete'`, `v = 1`). It
  was still there after close and reopen of a file database.
- An unknown FTS5 option raises "SQL logic error", which is what SQLite
  before 3.42 reports for `secure-delete`; the probe treats it as "no index".
- `recursive_triggers` off: the cascade still fired the FTS delete triggers.
- FTS5 prefix-compresses terms in `_data`, so the byte oracle needs a
  positive control and terms with distinct first letters.
- unicode61: `Ⅻ`, `x²`, `½` are tokens and match; `請求書` inside
  `東京都の請求書` is not found by MATCH but is by LIKE; LIKE found
  `รับเงิน` and `पत्र` as substrings; `%Ärzte%` found `Ärzte`, `%ärzte%` did not.
- A failed create (correction triggers on a missing table) followed by the
  drop left no FTS object in `sqlite_master`.
- Pinned `libsqlcipher.so` (arm64-v8a) compile options: no `ENABLE_ICU`,
  no `SECURE_DELETE`; so `lower()` is ASCII-only and `PRAGMA secure_delete`
  defaults to off.

### CI caveat: host SQLite version

The JVM job runs on `ubuntu-22.04` and installs no Python of its own, so
`python3` is the distro's, linked to the distro SQLite (believed 3.37.2;
not checked on the runner; below 3.42 either way), which has no FTS5
secure-delete. On that host the migration correctly creates no index, and
about 12 of the 15 `SearchIndexHostTest` cases (every one that goes through
`SearchFixture.migrated`, including the 8 FTS cases that ran on 22.04
before this round) are **skipped** by `SearchFixture.assumeIndexed` with a
message naming the version, not run. A green CI therefore does not verify
W-01. The cheapest real check is `./gradlew :vault:test` on this VM (host
SQLite 3.53.3) when the build slot is free. The tests that do not need the index (the LIKE fallback,
`aFailedCreateLeavesNoPartialIndex`, the sanitiser and snippet unit tests)
still run. For the W-01 oracle to run in CI the JVM job needs SQLite 3.42+
(for example `ubuntu-24.04`, SQLite 3.45). Owner/coordinator decision; not
changed here.

### What changed

- W-01: `SearchIndex.statements()` sets `secure-delete` after each
  `CREATE VIRTUAL TABLE`; `create` probes FTS5 and the option on a temp
  table and, on any failure, drops every index table and trigger (fail
  closed). `SearchIndex.ensure`, run by `SearchIndex.callback` at every
  open (added in `OpenLifeDatabaseFactory`), recreates an incomplete
  index. The open pragmas are unchanged (`PRAGMA secure_delete` stays off;
  residual recorded in THREAT_MODEL, C1-R10 and ADR-0010).
- W-02: sanitiser token characters follow unicode61; scripts without word
  spacing and Indic marks route to the LIKE substring path; limits
  disclosed in ADR-0010 and C1-R10.
- W-03: the sanitised expression runs directly on the engine with no catch;
  raw `parcel OR zebra` is shown to widen and the sanitised search not to.
- W-04: `NonEntityTables.assertOnlySearchIndex` (vault androidTest) asserts
  that the only non-entity tables after migrating are the two FTS tables
  and their `_data`, `_idx`, `_docsize` and `_config` shadow tables; used in
  the three `OcrDatabaseTest` migrations and in `SearchRepositoryTest`.
- W-05: LIKE patterns lowercase ASCII only, matching SQLite's `lower()`.
- W-06: `SearchScreenTest` restores the first-run flag in `finally`, skips
  (Assume) when app lock is on, and drives the real `OpenLifeNavHost`
  (span id on the viewer route; compact Back to results; expanded detail
  pane after the pop to the list). Unexercised: the vault query behind the
  screen (covered by `SearchRepositoryTest`) and `SearchViewModel`'s flow
  (the host gets a fixed `SearchHostState`).

### Changed existing tests (this round)

`SearchIndexHostTest` (byte oracle in the delete/cascade/update tests;
sanitiser test rewritten; W-05 assertions in the fallback test; new tests
for reopen, ensure, fail-closed, scripts), `SearchFixture` (oracle,
`assumeIndexed`), `HostSqlite` (query/transaction proxy, file path,
comment), `SearchQueryTest`, `SearchSnippetTest`, `SchemaV5MigrationTest`
(secure-delete statement order), `SearchRepositoryTest`, `OcrDatabaseTest`
(three migrations), `SearchScreenTest` (rewritten through the NavHost; the
old hand-wired test is replaced).

### Device checks still owed (reviewer's list)

1. `FtsAvailabilityTest`, plus a `PRAGMA compile_options` dump from the
   device's SQLCipher.
2. Upgrade a real v4 install that has data: the app opens, the list loads,
   and search finds existing text.
3. Cascade delete on SQLCipher, then scan `_data` for the term
   (`SearchRepositoryTest.deletingASourceRemovesItsHits` /
   `cascadeOnlyDeletionLeavesNoFtsRows`).
4. Latency for 500 spans on the device (target under 200 ms).
5. TalkBack reads each result as one node, and a result opened at expanded
   width selects the span.

Room's runtime acceptance of the non-entity FTS tables (no "Unexpected
table" or identity failure at open, and `validateDroppedTables = false`
validating the rest) is believed from reading room 2.8.5, and is
unconfirmed until a device run.

## Local run of the index tests after the review fixes (2026-11-25)

Hosted CI's SQLite may be older than 3.42, in which case the index cases
skip there, so the suite was run on this VM (python3 SQLite 3.53.3) at the
review-fix head:

- First run: `SearchIndexHostTest.cascadeOnlyDeletionLeavesNoFtsRows` failed
  on its positive control. Cause: the byte scan looked for whole terms, but
  FTS5 prefix-compresses each term against its predecessor ("lamp" then
  "lighthouse" stores "ighthouse"). `SearchFixture.dataContains` now scans
  for the term's tail.
- After the fix: `./gradlew :vault:detekt :vault:testDebugUnitTest` =>
  BUILD SUCCESSFUL, vault 191 tests, 0 skipped, 0 failed
  (`SearchIndexHostTest` 15/15).
- Recorded mutation: setting `secure-delete` to 0 on both tables makes 6 of
  15 `SearchIndexHostTest` cases fail, including the three deletion cases
  that scan `<table>_data`; reverted.

This is host SQLite, not SQLCipher; the on-device checks above still stand.

## Engine floor: FTS5 secure-delete corruption on SQLite 3.42.0-3.46.0 (PR #36, 2026-11-26)

PR #36 moved the CI build job to ubuntu-24.04, whose `python3` SQLite is
3.45.1 (printed by the job). Three `SearchIndexHostTest` cases failed there:
- `deletingASourceRemovesItsHits`
- `cascadeOnlyDeletionLeavesNoFtsRows`
- `updatesAndRevisionDeletionKeepTheIndexConsistent`

Each failed at its FTS5 `'integrity-check'` with "database disk image is
malformed".

**Reproduction** (scratchpad, not committed). A Java harness speaks
`HostSqlite`'s line protocol over `org.xerial:sqlite-jdbc`. The JARs were
downloaded from Maven Central and each checked against its published
`.sha1`. `OPENLIFE_HOST_SQLITE` selects the harness.

**Minimal sequence** (table and trigger SQL as in `SearchIndex`):

```sql
CREATE TABLE spans(id TEXT PRIMARY KEY NOT NULL, text TEXT NOT NULL);
CREATE VIRTUAL TABLE fts USING fts5(text, content='spans', content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2');
INSERT INTO fts(fts, rank) VALUES('secure-delete', 1);
-- AFTER INSERT / AFTER DELETE triggers as in SearchIndex
INSERT INTO spans VALUES('s1', 'orchard');
DELETE FROM spans WHERE id = 's1';
INSERT INTO fts(fts) VALUES('integrity-check');   -- SQLITE_CORRUPT_VTAB on <= 3.46.0
```

| SQLite | integrity-check after one insert and its delete |
|---|---|
| 3.42.0, 3.43.2, 3.44.1, 3.45.1, 3.45.3, 3.46.0 | corrupt (plain and rank 1) |
| 3.46.1, 3.47.0, 3.49.1, 3.50.3, 3.53.3 | ok |
| any of the above, without secure-delete | ok |
| internal-content FTS5 with secure-delete, any of the above | ok |

**Writer versus checker.** The database file was written by one engine and
checked by another:
- written by 3.45.1 or 3.46.0, checked by 3.46.1 or 3.53.3: corrupt;
- written by 3.46.1 or 3.53.3, checked by 3.45.1 or 3.46.0: ok.

So the older engines write a corrupt index; it is not a false alarm from
their checker. MATCH queries still returned correct rowids, so the
corruption is silent unless the check runs.

**Classification: an engine defect fixed by 3.46.1.**
- The `'delete'` commands pass exactly the indexed values.
- No path issues a `'delete'` for an unindexed rowid: no `REPLACE` or
  upsert touches `ocr_spans` or `ocr_user_revisions`.
- The same statements are clean from 3.46.1 on.
- No release note or check-in reference was available offline.

**3.53 is not silently affected.**
- `everyTriggerPathLeavesAnIndexThatPassesIntegrityCheck` passes on
  3.53.3 for v5, fresh v6 and migrated v6, checking after every step.
- So does every other mutation sequence in `SearchIndexHostTest`, which
  now ends with the check.

**Change.** `SearchIndex.MIN_SQLITE_VERSION` = 3.46.1:
- `create` probes `sqlite_version()`, and `isComplete` re-checks it at
  every open. On an older engine, or when the version is unreadable, no
  index is created or kept, and search uses the LIKE fallback.
- `SearchIndexEngineGateTest` presents chosen versions through
  `sqlite_version()`.
- RED commit: 3 failures on 3.53.3, 7 on 3.45.1.
- Device: SQLCipher 4.19.0 bundles SQLite 3.53.4 (static read, not
  observed). SQLCipher has been pinned at 4.19.0 since the first commit,
  so no existing index was written by an affected engine and no rebuild
  is needed.

**CI.** No GitHub-hosted Ubuntu image's system `python3` links SQLite
3.46.1 or later (22.04 has 3.37.2, expected; 24.04 has 3.45.1, printed).
- On ubuntu-24.04 the index cases are skipped again, with a message naming
  the version.
- The "Require a host SQLite that runs the search-index tests" step still
  checks for 3.42. It is not raised here: on 24.04 that would fail the
  job, and every option for a newer engine adds a build input, which needs
  an owner or coordinator decision.
- Options, smallest first. None is implemented.
  1. Add `org.xerial:sqlite-jdbc` (3.46.1 or later, with `slf4j-api`) as a
     `testImplementation` dependency, with verification metadata. A
     test-only `HostSqlite` engine over JDBC would replace the `python3`
     bridge, pinning the engine in Gradle on every host. This adds a test
     dependency and needs a decision record.
  2. In the CI job, download a pinned SQLite amalgamation (checked by
     SHA3-256), compile `libsqlite3.so` with FTS5, and run Gradle with
     `LD_LIBRARY_PATH` pointing at it, so the system `python3` loads it.
     There is no Gradle dependency, but the build downloads a tool.
  3. Run the build job on a runner whose `python3` links a newer SQLite
     (for example a macOS image with Homebrew Python). That is a larger and
     costlier change to the job.

**Results after the change.** Each line is `./gradlew :vault:testDebugUnitTest --rerun`:

| Host SQLite | Tests | Failed | Skipped |
|---|---|---|---|
| 3.53.3 | 237 | 0 | 0 |
| 3.46.1 (harness) | 237 | 0 | 0 |
| 3.45.1 (harness) | 237 | 0 | 14 (index cases, with the version-naming message) |

The full gate on 3.53.3 was BUILD SUCCESSFUL:
`detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`,
with vault 237, app 211 and eval 26 tests.

`FtsAvailabilityTest.bundledSqliteKeepsASecureDeleteExternalContentIndexIntact`
was added and compiled, but not run (no device).

**Device checks added.**
- Run `FtsAvailabilityTest.bundledSqliteKeepsASecureDeleteExternalContentIndexIntact`.
- After the delete and update sequences on SQLCipher, run
  `INSERT INTO <t>(<t>) VALUES('integrity-check')` and
  `INSERT INTO <t>(<t>, rank) VALUES('integrity-check', 1)` on both index
  tables.
- Read `sqlite_version()` on an opened vault and record it.
