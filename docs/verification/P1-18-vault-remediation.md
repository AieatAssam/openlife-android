# P1-18 Vault audit remediation — verification

Source: `docs/reviews/2026-11-24-vault-audit.md` (audit of 190be81).
Branch `plan/P1-18-vault-remediation`. Date 2026-11-24.

> File name: the step file (`docs_to_update`, R18) names
> `P1-18-vault-audit-remediation.md`; the coordinator's later instruction
> named this file, `P1-18-vault-remediation.md`. This is the one record; the
> mismatch is reported to the owner rather than resolved by duplicating it.

## Device policy for this run

Owner instruction (2026-11-24): **do not use the Android emulator at all and
do not touch the attached physical tablet.** No emulator was started in this
step (confirmed with `pgrep -fa qemu-system`), and no earlier connected result
exists for this branch. Therefore:

- Every instrumented test below was **compiled**
  (`./gradlew :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`
  => success) but **not run**. Its status is **gap — pending physical-device
  run**, never "assumed" or "passed".
- Where the logic could be moved behind a pure seam, a JVM test proves it and
  that JVM result is the evidence cited.

## Commits

| Phase | Commit | Content |
|---|---|---|
| RED | 3f86512 | failing JVM tests + instrumented tests (compiled) + stubs |
| GREEN | 2be07bc | remediation |
| REFACTOR | b42d935 | bootstrap calls `KeystoreFailures` directly; READY commit and I/O mappers moved to top-level helpers; detekt fixes (GREEN 2be07bc failed detekt with 61 formatting/complexity issues, fixed here). The trigger call moving to the end of `MIGRATION_1_2` landed in 2be07bc, not here as b42d935's message says. |

## JVM results

- RED (3f86512): vault 105 tests, 20 failures; app 65 tests, 2 failures —
  every failure for its intended reason (`NotImplementedError` stubs, probe
  OOM at `-Xmx22m`, `tryMutation` waited for readers, key written in place,
  `valueOf` crash on an unknown enum).
- GREEN (2be07bc): `./gradlew :vault:testDebugUnitTest :app:testDebugUnitTest`
  => vault 105/105, app 65/65; both androidTest source sets compile.
- VERIFY (b42d935): `./gradlew detekt lint :app:test :vault:test assembleDebug
  :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`
  => BUILD SUCCESSFUL; vault 105/105, app 65/65 JVM tests.

Recorded mutation (R7 guard that already passed on the unchanged tree):
swapping `mostSignificantBits`/`leastSignificantBits` in `EnvelopeAad.build`
made `GoldenEnvelopeFixtureTest.forSourceAadArtefactAndWrappedDekMatchCheckedInBytes`
fail (1 of 3 tests failed, the other two still passed); reverted.

## Findings

Outcome wording (review R-04): **fixed** means a test that ran proves it
(JVM). **implemented; unverified (gap — pending physical-device run)**
means the only proof is an instrumented test that compiles but has not run.

| Finding | Req | Outcome | Proof |
|---|---|---|---|
| V-01 transient Keystore → CORRUPT | R1 | fixed (classifier, JVM); end-to-end implemented; unverified (gap — pending physical-device run) | JVM `KeystoreFailuresTest` (4) ✔; device `KeystoreWrapperTest.nonAeadCipherFailureIsTransientNotAuthentication`, `SourceViewRepositoryTest.keystoreHiccupAtDoFinalLeavesRowReady` |
| V-01 bootstrap KEY_UNWRAP_FAILED | R2 | fixed (JVM); device implemented; unverified (gap — pending physical-device run) | JVM `KeystoreFailuresTest.platformKeyStoreExceptionAnywhereInTheChainIsTemporary`, `BootstrapPlanTest.aFailedTagOnTheKeyFileIsNotRetryable` ✔; device `VaultBootstrapperTest.androidKeyStoreExceptionAtUnwrapIsRetryable`, `…platformKeyStoreExceptionInTheCauseChainIsRetryable`, `…failedTagWithAKeystoreCauseAtUnwrapIsNotRetryable` |
| V-01 no way back from CORRUPT | R3 | implemented; unverified (gap — pending physical-device run) | device `VerifyAllTest.corruptRowThatNowAuthenticatesIsRestoredToReady`, `…WithTransientCheckStaysCorruptAndUnchanged` |
| V-02 lost DB → blobs deleted | R4 | fixed (plan, JVM); end-to-end implemented; unverified (gap — pending physical-device run) | JVM `BootstrapPlanTest` (8) ✔; device `VaultBootstrapperTest.missingDatabaseWithKeyAndArtefactsIsDatabaseMissingAndDeletesNothing`, `…savedOriginalsWithoutKeyOrDatabaseAreDatabaseMissingAndKept`, `…zeroByteDatabaseNextToSavedOriginalsIsDatabaseMissing`, `RecoveryRepositoryTest.emptyDatabaseNextToSavedOriginalsDeletesNothingAndReportsIt` |
| V-03 slot released on kept stage | R5 | fixed (rule, JVM); end-to-end implemented; unverified (gap — pending physical-device run) | JVM `ImportSlotTest` (4) ✔; device `ImportRepositoryTest.failedSaveThatKeepsTheStageKeepsTheSlot`, `RecoveryRepositoryTest.cleanAbandonedStagesSkipsAStageWhoseSaveFailedTransiently` |
| V-04 provider read under lease | R6 | fixed (`tryMutation`, JVM); read-outside-lease implemented; unverified (gap — pending physical-device run) | JVM `MutationQueueTest.tryMutationReturnsBusyImmediatelyWhenReadersAreActive` ✔; device `ImportRepositoryTest.slowProviderReadDoesNotBlockViewerReadsOrDeletion` |
| V-06 forSource AAD unpinned | R7 | fixed | JVM `GoldenEnvelopeFixtureTest.forSourceAadArtefactAndWrappedDekMatchCheckedInBytes` ✔ + recorded mutation |
| V-07 OCR transient → SOURCE_CORRUPT | R8 | fixed (mapping, JVM); end-to-end implemented; unverified (gap — pending physical-device run) | JVM `OcrCaptureFailureTest` (2) ✔; device `OcrRepositoryTest.transientAuthenticationFailureIsRetryableNotSourceCorrupt` |
| V-08 cancellation swallowed | R9 | fixed for save (JVM, mutation-proven); delete implemented; unverified (gap — pending physical-device run) | JVM `SaveCommitTest` (2) ✔; device `ImportRepositoryTest.cancellationAfterReadyCommitPropagatesAndRowIsReady`, `DeletionRepositoryTest.cancellationIsNotSwallowedByGenericCatch` (both now throw from the DAO seam, review R-02) |
| V-09 torn key file | R10 | fixed (JVM); end-to-end implemented; unverified (gap — pending physical-device run) | JVM `VaultKeyFileTest` (2), `BootstrapPlanTest.tornKeyWithNothingAtStakeIsAFreshInstall` ✔; device `VaultBootstrapperTest.tornKeyFileWithNoDatabaseAndNoArtefactsIsFreshInstall` |
| V-10 stray file blocks reset | R11 | fixed (wipe, JVM); end-to-end implemented; unverified (gap — pending physical-device run) | JVM `VaultDirectoryWipeTest` (3, incl. symlink) ✔; device `VaultResetRepositoryTest.strayNonUuidFileAndJournalDoNotBlockReset` |
| V-11 triggers only on create | R12 | implemented; unverified (gap — pending physical-device run) | device `OcrDatabaseTest.readyInvariantTriggersExistAfterMigratingToCurrentVersion`; its mutation also needs the device |
| V-12 hard-coded file ops in recovery | R15 | implemented; unverified (gap — pending physical-device run) | device `RecoveryRepositoryTest.injectedFileOpsFaultReachesRecovery` |
| V-14 schema version check | R17 | **deferred** | P2-06 (PR #23) has not merged; the step says to leave it for a follow-up |
| V-16 synchronous level | R16 | FULL set on the writer connection (JVM-pinned); effective value implemented; unverified (gap — pending physical-device run) | JVM `DurableWritesTest` ✔; device `DatabasePragmaTest` (WAL/AUTOMATIC and TRUNCATE) |
| VS-1 CAS transitions | R13 | fixed for the save commit (JVM); DAO implemented; unverified (gap — pending physical-device run) | JVM `SaveCommitTest` ✔; device `SourceDaoTest.compareAndSetStateTransitionLosesRaceExplicitly` |
| VS-1 tolerant enum decoding | R14 | fixed (JVM); recovery path implemented; unverified (gap — pending physical-device run) | JVM `EnumDecodingTest` (3), app `OcrStateMapperTest` (3) ✔; device `RecoveryRepositoryTest.unknownSourceStateIsNeitherShownNorDeleted` |
| CI OOM (run 45405099804) | addition | reader fixed (JVM); typed failure implemented; unverified (gap — pending physical-device run) | see below |

Findings refuted: none. V-16 was decided by the coordinator on the static evidence below; the observed value is still pending.

### R1 — what AOSP throws

`AndroidKeyStoreCipherSpiBase.engineDoFinal` (read at tags
`android-10.0.0_r1` and `android-14.0.0_r1`): `KM_ERROR_VERIFICATION_FAILED`
→ `AEADBadTagException`; `KM_ERROR_INVALID_ARGUMENT` → `BadPaddingException`;
every other keymaster error → `IllegalBlockSizeException` whose cause is
`android.security.KeyStoreException` (public only from API 33, so matched by
class name). Only `AEADBadTagException` is now authentication
(`KeystoreFailures.forCipherFailure`); everything else is
`KeystoreUnavailableException`. `KeystoreFailures` is the single classifier
used by `KeystoreWrapper` and `VaultBootstrapper`.

### R16 / V-16 — FULL set by coordinator decision (review R-03)

Read from the sqlcipher-android 4.19.0 bytecode (`SQLiteGlobal`,
`SQLiteConnection`): `getDefaultJournalMode()` = `delete`,
`getDefaultSyncMode()` = `normal`, `getWALSyncMode()` = `normal`;
`SQLiteConnection.open()` calls `setWalModeFromConfiguration` (journal mode
then `setSyncMode`) after the hook's `postKey`, so a `SQLiteDatabaseHook`
cannot override it. Under NORMAL in WAL mode an acknowledged commit can be
rolled back by power loss; a READY commit the user saw as saved could come
back STAGED and recovery would then delete it.

Coordinator decision (review R-03): `DurableWrites.callback` runs
`PRAGMA synchronous = FULL` in Room `onOpen`, which runs on the writer
connection after SQLCipher's own post-open settings, on every open, in WAL
and in TRUNCATE (low-RAM `AUTOMATIC`) journal mode. Reader connections keep
NORMAL; they never commit.

- JVM: `DurableWritesTest.everyOpenSetsFullSynchronousOnTheWriterConnection` ✔
  (failed on the RED commit: `expected:<[FULL, FULL]> but was:<[]>`).
- Device: `DatabasePragmaTest` (AUTOMATIC/WAL, and
  `synchronousIsFullInTruncateJournalModeToo`) — implemented; unverified
  (gap — pending physical-device run).
- Write latency of FULL vs NORMAL — **not measured**; gap pending a
  physical-device run.

### CI-found addition — OOM on API 29 (run 45405099804)

`ImportRepositoryTest.excessBytesAreRejectedAndLeaveNoStagedState` died with
`OutOfMemoryError` in `BoundedStreamReader` (doubling `ByteArrayOutputStream`)
on the API 29 heap (growth limit 16 MiB).

1. `BoundedStreamReader` now accumulates 64 KiB chunks (no doubling copies),
   stops at limit + 1, and for an oversized input returns `byteCount` /
   `exceededLimit` with empty bytes and wiped chunks. Peak ≈ N + 64 KiB for an
   oversized read, ≈ 2N for an accepted one. JVM proof:
   `BoundedStreamReaderTest.oversizedReadFitsInAHeapThatCannotHoldTwoCopies`
   (forked JVM, `-Xmx22m`; RED: OOM exit 3; GREEN: pass) and
   `stopsAtLimitPlusOneForOversizedInput`.
2. `prepareImport` catches `OutOfMemoryError` around read and stage
   encryption, cancels any stage under a lease, and returns
   `PrepareResult.Failed` (existing category). Device test
   `ImportRepositoryTest.outOfMemoryDuringReadOrStageEncryptionIsATypedFailureWithNoStage`:
   gap — pending physical-device run.
3. **Plainly:** with a 16 MiB growth limit, holding 16 MiB + 1 of chunks plus
   the app's baseline does not fit, so on the API 29 CI heap the oversized
   case may still exhaust memory; it will now surface as `Failed` (no crash,
   no stage), not `Rejected(EXCEEDS_BYTE_LIMIT)`. The CI test expecting
   EXCEEDS_BYTE_LIMIT may therefore still fail on API 29; this needs a CI run.

## Other deviations / owner questions

- Two existing tests were changed in RED 3f86512:
  `VaultBootstrapperTest.corruptKeyFileIsUnavailableNotReplaced` and
  `…eachFailureModeReportsItsTypedCause` now write a database file before
  corrupting the key. Under R10, a torn key file with no database and empty
  `artefacts/` is a fresh install, so the corrupt-key expectation holds only
  when a database exists.
- A missing `artefacts/` directory cannot reach `planBootstrap`, because
  `bootstrap` calls `ensureDirectoriesExist()` first. "Unlistable" therefore
  means I/O failure, which counts as "not provably empty".
- No full-row `sourceDao.update(...)` remains in `vault/src/main` or
  `app/src/main` (grep).

- No key, no database, artefacts present still bootstraps fresh and recovery
  then removes the artefacts as orphans (V-02-shaped, outside R4's wording).
  Owner question: should that also be `DATABASE_MISSING`-like?
- Because `tryMutation` no longer waits for readers, reset can return BUSY
  while a viewer holds a read lease.
- `@Update SourceDao.update` remains for test fixtures only (KDoc says so);
  `OcrDao.updateRevision` was removed.

## Device-verification gaps (exact unrun commands)

Status of each: **gap — pending physical-device run**.

```bash
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.crypto.KeystoreWrapperTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.SourceViewRepositoryTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.VaultBootstrapperTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.VerifyAllTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.ImportRepositoryTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.RecoveryRepositoryTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.DeletionRepositoryTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.OcrRepositoryTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.VaultResetRepositoryTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.OcrDatabaseTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.SourceDaoTest
./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.storage.DatabasePragmaTest
./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest   # full suites (app UI touched: OcrPanel, VaultUnavailableScreen)
```

Plus the R12 mutation (remove `ReadyInvariantTriggers.create(db)` from
`MIGRATION_1_2`, run `OcrDatabaseTest`, expect failure, revert) and the CI
API 29/36 legs (P0-04) once the owner pushes; no CI run id exists for this
branch.

## Review round (R-01..R-09)

RED commit `4bda2cd`: vault JVM 118 tests, 11 failed, 0 skipped, each
for the intended reason (`KeystoreFailuresTest` ×2, `BootstrapPlanTest` ×3,
`DurableWritesTest`, `BoundedStreamReaderTest` (1002 chunks allocated),
`VaultDirectoryWipeTest` (symlink target deleted), `ImportSlotTest`,
`OrphanPolicyTest` ×2).

GREEN + REFACTOR `1a47912`: `./gradlew detekt :vault:test :app:test
:vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`
=> BUILD SUCCESSFUL; vault JVM 118/118, app JVM 65/65, 0 skipped. Not run
in this round: `lint`, `assembleDebug`, and any connected test (owner
instruction: no emulator, no device). `planBootstrap` lost its unused
`keyFileExists` parameter (the R-05 rule no longer needs it).

| Finding | Change | Proof |
|---|---|---|
| R-01 | `isTemporary` is false when the chain holds `AEADBadTagException`, `KeyPermanentlyInvalidatedException` or `MissingWrappingKeyException`; a permanently invalidated key maps to `MissingWrappingKeyException`. AOSP `AndroidKeyStoreCipherSpiBase.engineDoFinal` throws `new AEADBadTagException().initCause(KeyStoreException)` (android-10.0.0_r1 `keystore/…/keystore/` line 517; android-14.0.0_r1 `keystore/…/keystore2/` line 626). Platform exception instances are built with `Unsafe` and the tests use `Assume`, never a silent skip; the bootstrap device test's silent if/else was removed. | JVM ✔; device tests implemented; unverified (gap — pending physical-device run) |
| R-02 | Cancellation tests throw a synthetic `CancellationException` from the DAO after the commit. | JVM `SaveCommitTest` passed on RED too, so proven by mutation: removing the `CancellationException` rethrow in `commitReady` made `cancellationAfterTheReadyCommitReachesTheCaller` fail (`expected CancellationException, got Failed`), reverted. Device tests implemented; unverified (gap — pending physical-device run) |
| R-03 | See R16 above. | as above |
| R-04 | Wording in this record and `plan.yaml` corrected. | — |
| R-05, R-08 | Bootstrap: no database, or a 0-byte database, next to saved originals is `DATABASE_MISSING`, whatever the key file says. Recovery: `OrphanPolicy` never removes orphans when the database has zero rows and a `.blob` exists; the count is reported as `keptBlobsWithoutRows` and the app answers `DATABASE_MISSING` instead of an empty vault. | JVM `BootstrapPlanTest`, `OrphanPolicyTest` ✔; device tests implemented; unverified (gap — pending physical-device run) |
| R-06 | The slot rule reads the row and stage only for `Failed`/`StorageUnavailable`; `cancelStage` deletes only a STAGED row (`deleteStaged`, `AND state='STAGED'`); `DuplicateVerifier` (lost race → `Unverifiable`) and `SourceViewRepository` (lost race → `Unavailable`) handle a zero-row compare-and-set. | JVM `ImportSlotTest` ✔; rest implemented; unverified (gap — pending physical-device run) |
| R-07 | No chunk allocation for zero-byte reads at a boundary; the OOM catch now covers only the bounded read and the stage encryption. | JVM `BoundedStreamReaderTest` ✔ |
| R-09 | `VaultDirectoryWipe` removes a symlink without following it. The reset `isOccupied` check-then-act window is **accepted** (coordinator decision, review R-09): an import starting between the check and the wipe fails its save and the stage is gone with the wipe. | JVM `VaultDirectoryWipeTest` ✔ |

