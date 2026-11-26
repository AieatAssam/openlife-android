# P3-02 Fact model, schema, migration, cascade

Branch `plan/P3-02-fact-schema`, cut from `main` at 31cc2eb. Date
2026-11-26. Implements the storage part of docs/capabilities/C2.md
(§4, §5, §8, §9, §10). Fixes finding F-70.

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
| `FactDatabaseSqlCipherTest.versionFiveVaultWithRowsMigratesToTheC2SchemaOnSqlCipher` (v5 to v6 with rows, `MigrationTestHelper`, SQLCipher) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.FactDatabaseSqlCipherTest` |
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
`foreign_keys` and `recursive_triggers` on). **Host SQLite is not
SQLCipher.** The schema DDL is taken from the exported
`vault/schemas/.../6.json`, so the host database has the same tables and
indices Room creates.

| Test class | What it shows |
| --- | --- |
| `FactDatabaseTest` (5) | v5 to v6 migration keeps every v5 row and the FTS data byte for byte, and gives the same shape as a fresh v6 database; every C2 CHECK trigger refuses with its requirement id; the C2-R20 pointer guards; the FTS update triggers are narrowed before any backfill `UPDATE` (C2-R21, C2-R64) |
| `DependencyEdgeTest` (4) | cross-Source edges refused (C2-R18); `BASIS` only to an older non-USER revision of the same fact; the `EVIDENCE` edge goes when the last evidence row naming the span goes; no Kotlin statement writes `dependency_edges` |
| `SourceRowRemovalTest` (3) | F-70: one routine, whole-set order of C2-R43, same final database with `foreign_keys` on and off; refuses a non-DELETING Source before running any statement; the pre-C2 order is refused; only the routine and the STAGED cleanup remove a Source row |
| `OcrRevisionPointerTest` (1) | `sources.currentOcrRevisionId` moves only forward to a later READY reading of the Source (C2-R14, F-69 for facts); corrections supersede the chain head explicitly (C2-R15) |
| `OcrSpanStatusTest` (2) | `hasEvidenceRegion` and `scriptStatus` backfilled, eligibility per C2 §1 (Z-16); a classifier failure rolls the whole migration back (C2-R17) |
| `FactWriterTest` (5) | the bodies of `FactRepository`: the §5 status table, clarify byte for byte and re-choose (C2-R36), correct refused on STALE (C2-R37), user facts (C2-R38) and the 500 cap (C2-R62), the BFS stale traversal with BASIS_STALE and the epoch bump on every call (C2-R19, C2-R63) |
| `SchemaV5MigrationTest`, `EnumDecodingTest` | v5 tests updated for the narrowed triggers; tolerant decoding of the new enums |

## Mutations

- Removing `NEW.x IS NOT OLD.x` guards: `UPDATE OF` fires on host SQLite
  even for an unchanged value, so the pointer triggers refused
  same-value updates (seen while writing the triggers).
- RED (commit 5c328bf): 22 of 30 targeted tests failed, each for the
  intended reason (no 6.json, TODO stubs, boundary checks, narrowed
  triggers missing).

## Deviations recorded in the step notes

See `plan/steps/P3-02.yaml` notes and C2.md §19 (D-23..D-26).
