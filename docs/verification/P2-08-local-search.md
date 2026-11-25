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
| `SearchRepositoryTest` (5 brief-named tests + `roomValidatesAMigratedDatabaseWithTheFtsTablesPresent`) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.search.SearchRepositoryTest` |
| `SearchScreenTest.resultOpensViewerWithSpanSelectedAndFieldClearsOnStop` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.search.SearchScreenTest` |
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
`foreign_keys` and `recursive_triggers`, so `ON DELETE CASCADE` fires the
FTS delete triggers as it does on the device. `SearchIndexHostTest` does
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
