# P2-07 Communication assessment state scaffold

Branch `plan/P2-07-assessment-state`, based on the head of
`plan/P1-19-app-remediation` (556c867). Date 2026-11-25. Closes F-17
(design §6: explicit assessment state from C1 on).

## Device policy for this run

Owner instruction: no emulator, no `connected*AndroidTest` task, no `adb`
(the attached tablet belongs to another agent). None was started or run.
Every instrumented test below was **compiled**
(`./gradlew :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`
=> BUILD SUCCESSFUL) and **not run**. Its status is
**implemented; unverified (gap — pending physical-device run)**. Robolectric
was not added. The step's own verification command
(`./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest`)
was therefore not run.

## What changed

- Schema **v4** (`@Database(version = 4)`, `Vault.SCHEMA_VERSION = 4`):
  `sources.assessmentState TEXT NOT NULL DEFAULT 'NOT_ASSESSED'`,
  `userContentKind TEXT`, `userContentKindAt INTEGER`,
  `assessmentMethodVersion TEXT`. `MIGRATION_3_4` is four `ALTER TABLE ...
  ADD COLUMN` statements followed by `ReadyInvariantTriggers.create(db)`
  (P1-18-R12). No table is added or rebuilt.
- `AssessmentState` (five design §6 values + `UNRECOGNISED`) and
  `UserContentKind` (`COMMUNICATION`, `NOT_COMMUNICATION` + `UNRECOGNISED`)
  decode tolerantly (P1-18-R14); `toEntity` refuses to write `UNRECOGNISED`.
- `SourceDao.setUserContentKind` is a READY-only compare-and-set `@Query`
  (no `@Update`); `SourceViewRepository.setUserContentKind` runs it under the
  mutation queue on the IO dispatcher with an injected clock. It never
  writes `assessmentState`.
- Viewer details: "Safety check: not assessed in this version" with a plain
  explanation; "Is this a message or request from someone?" as a Yes / No /
  Not sure radio group (48 dp rows, `selectableGroup`, `Role.RadioButton`),
  "Answered by you: <time>" (time in the data face) and the routing copy
  "… It is routing context, not proof of safety." All strings are resources.
- Label rule: Not sure stores `userContentKind = null` with a timestamp;
  `userContentKindAt = null` means never answered. Picking the stored answer
  again writes nothing.
- A stored state other than `NOT_ASSESSED` (only another build can write
  one) is shown as "recorded by another version of OpenLife. This version
  cannot show the result." No per-state copy exists for results C1 never
  produces, so none can read as reassurance.

## Schema JSON diff (3.json -> 4.json)

```
4,5c4,5
<     "version": 3,
<     "identityHash": "fb65bffdbbef7570f28405d8a48e0491",
---
>     "version": 4,
>     "identityHash": "e306b4a786aeffc4c9d42f263e2059f7",
9c9
<         "createSql": "CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` (`id` TEXT NOT NULL, `state` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, `intakeKind` TEXT NOT NULL, `mimeType` TEXT, `byteCount` INTEGER, `sha256` BLOB, `width` INTEGER, `height` INTEGER, `orientation` TEXT, `wrappedDek` BLOB, `artefactVersion` INTEGER, PRIMARY KEY(`id`))",
---
>         "createSql": "CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` (`id` TEXT NOT NULL, `state` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, `intakeKind` TEXT NOT NULL, `mimeType` TEXT, `byteCount` INTEGER, `sha256` BLOB, `width` INTEGER, `height` INTEGER, `orientation` TEXT, `wrappedDek` BLOB, `artefactVersion` INTEGER, `assessmentState` TEXT NOT NULL DEFAULT 'NOT_ASSESSED', `userContentKind` TEXT, `userContentKindAt` INTEGER, `assessmentMethodVersion` TEXT, PRIMARY KEY(`id`))",
73a74,95
>           },
>           {
>             "fieldPath": "assessmentState",
>             "columnName": "assessmentState",
>             "affinity": "TEXT",
>             "notNull": true,
>             "defaultValue": "'NOT_ASSESSED'"
>           },
>           {
>             "fieldPath": "userContentKind",
>             "columnName": "userContentKind",
>             "affinity": "TEXT"
>           },
>           {
>             "fieldPath": "userContentKindAt",
>             "columnName": "userContentKindAt",
>             "affinity": "INTEGER"
>           },
>           {
>             "fieldPath": "assessmentMethodVersion",
>             "columnName": "assessmentMethodVersion",
>             "affinity": "TEXT"
457c479
<       "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'fb65bffdbbef7570f28405d8a48e0491')"
---
>       "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e306b4a786aeffc4c9d42f263e2059f7')"
```

`vault/schemas/.../4.json` and `vault/src/androidTest/assets/.../4.json` are
byte-identical (`SchemaV4MigrationTest.bothCopiesOfTheV4SchemaAreByteIdentical`).

## Evidence

| Phase | Commit | Result (this VM, 2026-11-25) |
|---|---|---|
| RED | dc73a8b | vault JVM 147 tests, 8 failed: `SchemaV4MigrationTest` 3 (no ADD COLUMN statements; no trigger re-assertion; androidTest 4.json missing), `SourceAssessmentMappingTest` 5 (mapper ignored the new columns: expected NOT_APPLICABLE was NOT_ASSESSED, expected 7 was null, UNRECOGNISED not decoded, no IllegalStateException on write-back). app JVM 125 tests, 6 failed: `ContentKindLabelTest` 5 NotImplementedError (TODO stubs) + 1 assertion on the placeholder routing string. Both androidTest sets compiled. |
| RED (boundary) | dc73a8b | `AssessmentStateBoundaryTest` passed on the tree, so RED by recorded mutations: (1) appending `internal val mutationProbe = AssessmentState.NO_INDICATORS_FOUND` to `ContentKindLabel.kt` failed it, reverted; (2) changing the DAO query to `UPDATE sources SET assessmentState = :kind, ...` failed it, reverted. |
| GREEN | 7c4df03 | vault 147/147, app 126/126 |
| REFACTOR | 4241f54, f2cd01e | import instead of qualified names; test property read without a platform-type warning |
| VERIFY | f2cd01e | `./gradlew :app:cleanTestDebugUnitTest :vault:cleanTestDebugUnitTest detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` => BUILD SUCCESSFUL; vault 147/147, app 126/126 (new: `SchemaV4MigrationTest` 5, `SourceAssessmentMappingTest` 8, `ContentKindLabelTest` 9, `AssessmentStateBoundaryTest` 1; `SchemaV3MigrationTest` 3 and `VaultTest` 1 unchanged and passing). An earlier `--rerun-tasks` attempt ended with "Gradle build daemon disappeared unexpectedly" and was re-run. `scripts/plan-check.sh` steps=82 errors=0; `bash scripts/tests/run.sh` 47 passed, 0 failed. Connected: none run (owner instruction). |

Existing tests changed: `SaveCommitTest` (its fake `SourceDao` must
implement the new `setUserContentKind`; it throws as `unused()` like the
other unused members) and `ViewerScreenTest` (existing class: new method,
helpers, imports and a companion object; its two original tests unchanged).
No assertion in an existing test changed.

Checked: no Compose effect is keyed on the whole `Source` (`rememberViewerContent`
keys on `source.id` and `source.state`; viewer state on `source.id`; list
thumbnails on `sourceId`), so a label write, which emits a new `Source`, does
not reload, re-authenticate or re-decode the image.

## Instrumented tests awaiting a device

Each: implemented; unverified (gap — pending physical-device run).

| Test | Command |
|---|---|
| `SourceDatabaseTest.migrationAddsAssessmentColumnsWithNotAssessedDefaultAndPreservesRows` (new; SQLCipher v3->v4, Room validation against 4.json, defaults, rows kept, triggers exist and fire, label CAS on READY only, UPDATE trigger accepts the label write) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.SourceDatabaseTest` |
| `ViewerScreenTest.userContentKindLabelPersistsWithTimestampAndShowsRoutingCopy` (new; real vault round trip, copy shown, Yes then Not sure persisted with timestamps, no node calls anything safe) | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.ViewerScreenTest` |
| `OpenLifeDatabaseReadyInvariantTest` (unchanged; brief: "still passes") | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.OpenLifeDatabaseReadyInvariantTest` |
| `OcrDatabaseTest` (unchanged; its upgrade tests migrate to the current version, now through `MIGRATION_3_4`) | `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.OcrDatabaseTest` |

Device checklist (manual, TalkBack): the radio group reads as one group with
three options and the selected one announced; the question is a heading;
at font scale 2.0 the rows wrap without clipping.
