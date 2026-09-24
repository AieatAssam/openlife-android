# P1-14 verification: bootstrap and recovery robustness

Date: 2026-09-24. Branch: `plan/P1-14-bootstrap-recovery`. Device: `dev36`
(API 36). API 29 and API 36 evidence comes from CI on the PR.

## RED (c74d3d9)

| Test | Failure on the unchanged tree |
| --- | --- |
| `DatabaseOpenFailureTest` (4 tests) | `VaultOpener.open` returned `Ready` for a garbage file, a wrong secret, `user_version = 99` and a throwing migration |
| `KeystoreWrapperTest.unwrapWithMissingAliasFailsAndDoesNotCreateAKey` | unwrap generated a new key and failed as `EnvelopeAuthenticationException` |
| `KeystoreWrapperTest.wrapWithMissingAliasFailsAndDoesNotCreateAKey` | wrap silently created a key |
| `VaultBootstrapperTest.missingAliasWithExistingKeyFileIsUnrecoverableAndCreatesNoKey` | bootstrap generated a replacement key |
| `RecoveryRepositoryTest.startupDoesNotDecryptReadyBlobsAndTransientKeystoreErrorLeavesRowReady` | startup unwrapped 1 key |
| `VerifyAllTest` (2 tests) | startup marked tampered ciphertext CORRUPT; `verifyAll` was a stub |
| `CoreFlowUxTest.settingsVerifyAllItemsRunsOnceAndShowsCounts` | no "Verify all items" node |

The guards `RecoveryRepositoryTest.missingOrMalformedBlobHeaderStillMarksCorrupt`
and `OcrDatabaseTest.migrationOnSqlCipherPreservesReadyInvariantTriggers`
passed before the change and are proven by mutation. Each mutation below made
its guard fail, and was then reverted:

- Recovery treats `Framing.BROKEN` as plausible: `expected:<CORRUPT> but was:<READY>`.
- `MIGRATION_1_2` drops `sources_ready_requires_fields_INSERT`: "a READY row
  with a NULL validated field must be rejected by the trigger: null".

The trigger test also has a positive control: a complete READY row must insert.
It asserts the trigger's `RAISE` message, so an unrelated SQL error cannot
make it pass.

## GREEN (9f2384b) and REFACTOR

- **Keys.** `KeystoreWrapper.ensureWrappingKey()` is the only code that
  generates a key. It is called only from `VaultBootstrapper.createFresh`,
  when no key file and no database exist.
  - `existingWrappingKey()` never creates a key.
  - `wrap` and `unwrap` throw `MissingWrappingKeyException`.
  - Bootstrap maps a missing alias with the key file present to
    `KEY_UNWRAP_FAILED` (not retryable).
  - Import maps any wrap failure to `PrepareResult.Failed`.
  - A missing key on a read is `ArtefactCheck.Transient`, never CORRUPT.
- **Open.** `VaultOpener.open` returns one `VaultOpenResult` covering bootstrap,
  SQLCipher open and migration, which it forces through
  `openHelper.writableDatabase`.
  - `SQLiteException`, `IllegalStateException` (Room's missing or failed
    migration), `IOException` and `UnsatisfiedLinkError` become
    `DATABASE_OPEN_FAILED`. Disk-full and disk I/O errors become
    `STORAGE_IO_ERROR`.
  - On failure the database is closed and nothing is deleted.
  - `OpenLifeApp.vault()` consumes this result.
- **Startup.** `recover()` reads only the first
  `EnvelopeCodec.FRAMING_PREFIX_BYTES` (22) bytes of each READY blob and
  checks the magic, version, nonce length and declared-versus-actual length.
  An I/O error there changes nothing. `confirmedReady` now means "blob present
  with consistent framing".
- **Verify.** `verifyAll()` deep-authenticates each READY item in its own
  mutation.
  - Only `Corrupt` or `Missing` marks the item CORRUPT.
  - `Transient` is counted and changes nothing.
  - Settings > Verify all items shows verified, damaged and could-not-check
    counts. A null result means the vault is unavailable, and a thrown error
    shows a failure line.
- **Changed test.** `RecoveryRepositoryTest.readySourceWithTamperedBlobIsMarkedCorrupt`
  became `readySourceWithTamperedCiphertextIsLeftForDeepVerification`, which
  follows the new shallow semantics: the item stays READY at startup and
  `verifyAll` marks it.
- **Refactor.** Detekt and lint fixes: plurals for the counts, and the picker
  launcher is extracted from `MainContent`.

No path generates a key except a fresh install:

```
$ grep -rn "KeyGenerator\|ensureWrappingKey" vault/src/main app/src/main
vault/src/main/java/org/openlife/vault/crypto/KeystoreWrapper.kt:13:import javax.crypto.KeyGenerator
vault/src/main/java/org/openlife/vault/crypto/KeystoreWrapper.kt:46:    fun ensureWrappingKey(): SecretKey {
vault/src/main/java/org/openlife/vault/crypto/KeystoreWrapper.kt:49:        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
vault/src/main/java/org/openlife/vault/storage/VaultBootstrapper.kt:136:            wrapper.ensureWrappingKey()
```

## SQLCipher never deletes a corrupt database

Room's `onCorruption` is not routed by sqlcipher-android 4.19.0's
`SupportHelper`, which passes a null error handler. The fallback
`net.zetetic.database.DefaultDatabaseErrorHandler.onCorruption` returns before
`deleteDatabaseFile` when `SQLiteDatabase.hasCodec()` is true, and it always
is under SQLCipher. Checked with `javap -c` on the AAR's `classes.jar`. A
corrupt page hit mid-session therefore does not delete the file either.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :vault:connectedDebugAndroidTest` | 114 tests: all pass after the tamper test was converted (first full run: 113/114, the expected semantic change) |
| `./gradlew :app:connectedDebugAndroidTest` | 84/84 |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL (after refactor) |
| `RecoveryTimingTest` (opt-in, `p114Timing=true`) | see below |

The app suite ran before the final refactor, which changed only the count copy
to plurals and extracted the picker launcher. CI reruns both suites on API 29
and API 36.

### Cold start with 50 sources (acceptance)

`RecoveryTimingTest` imports 50 distinct 1200x900 noise JPEGs (44,812,527 blob
bytes) and times `recover()` five times.

| Tree | `recover()` ms | median |
| --- | --- | --- |
| before (c74d3d9 main sources, deep startup authentication) | 1249, 1294, 1335, 1496, 1457 | 1335 |
| after (9f2384b) | 25, 12, 623, 11, 11 | 12 |

The single 623 ms run is an outlier on a shared 4-core host. The startup path
reads 22 bytes per item and touches no Keystore.

### Manual dd corruption

The step's command, run after the WAL had been checkpointed (WAL 0 bytes):

```
$ ls -la no_backup/vault (after force-stop)
-rw------- 1 u0_a226 u0_a226 61440 2026-09-24 06:26 openlife.db
-rw------- 1 u0_a226 u0_a226 32768 2026-09-24 06:26 openlife.db-shm
-rw------- 1 u0_a226 u0_a226     0 2026-09-24 06:26 openlife.db-wal
$ dd if=/dev/urandom of=no_backup/vault/openlife.db bs=1k count=4 conv=notrunc
4+0 records in
4+0 records out
4096 bytes (4.0 K) copied, 0.001 s, 3.9 M/s
$ sha256sum (before launch)
47c5f37fb9687591cf77f4468e8dfab74c5400f48e13befccc5b28efc3f31ff1  no_backup/vault/openlife.db
9ad5cfa6473fb639f10a99dc1e282e9ea2a305a76c5a4d183c6e9763fda159ef  no_backup/vault/database.key
$ am start
Status: ok
$ pidof org.openlife: 5969
$ uiautomator dump (visible text)
text="The vault is unavailable"
text="The encrypted database could not be opened. Your vault files have been left in place."
text="What this means"
text="Try again"
$ sha256sum (after launch)
47c5f37fb9687591cf77f4468e8dfab74c5400f48e13befccc5b28efc3f31ff1  no_backup/vault/openlife.db
9ad5cfa6473fb639f10a99dc1e282e9ea2a305a76c5a4d183c6e9763fda159ef  no_backup/vault/database.key
-rw------- 1 u0_a226 u0_a226 61440 2026-09-24 06:28 openlife.db
-rw------- 1 u0_a226 u0_a226 32768 2026-09-24 06:26 openlife.db-shm
-rw------- 1 u0_a226 u0_a226     0 2026-09-24 06:26 openlife.db-wal
$ logcat -b crash
(end crash buffer)
```

An earlier attempt corrupted page 1 while the freshly created vault's WAL still
held every page. The first open failed and showed the unavailable screen.
Closing that connection let SQLite checkpoint the committed WAL frames, which
rewrote page 1, and "Try again" then opened the vault. This is SQLite's normal
WAL recovery: it writes committed pages and deletes nothing, and the key file
was unchanged. It does mean "file untouched" holds only when the WAL is empty.

## Deviations and limits

- The brief asked for `EnvelopeAuthenticationException(cause=MissingKey)`.
  P1-13 later made `EnvelopeAuthenticationException` mean "the stored bytes are
  bad", which marks items CORRUPT, so a lost key uses its own
  `MissingWrappingKeyException` and is never evidence against an item.
- On a read, a lost key surfaces as Transient in the viewer. In practice the
  vault is already unavailable at bootstrap in that state, because the database
  secret cannot be unwrapped.
- API 29 evidence comes from CI.
