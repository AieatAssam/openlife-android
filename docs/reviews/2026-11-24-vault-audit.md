# vault/ module audit

- Date: 2026-11-24
- Reviewer: Claude Opus subagent (not an independent audit)
- Commit audited: `190be81` (`main`)
- Method: read-only reading of `vault/src/main`, a skim of `vault/src/test`
  and `vault/src/androidTest`, and the relevant app wiring. Nothing was
  run: no Gradle, emulator, adb or tests. Evidence claims in
  `plan/plan.yaml` were not relied on.

## Verdict

Nothing needs a full redo. The envelope format, AAD binding, nonce
handling, staged commit order, recovery table and deletion ordering all
match design §10–§12. The defects below are local fixes.

| Package | Verdict |
|---|---|
| crypto | keep, with one fix (V-01) and one test fix (V-06) |
| model | keep |
| storage | keep-with-fixes |
| repository | keep-with-fixes |
| ocr | keep-with-fixes: delete ML Kit now (V-05) |

## Findings

Severity order. "Confirmed" means confirmed by reading the code;
"suspected" means it depends on behaviour that was not verified.

### V-01 (High): a transient Keystore error can mark an intact item CORRUPT, permanently

- **Where:**
  - `crypto/KeystoreWrapper.kt:118-124` treats every
    `BadPaddingException`, `IllegalBlockSizeException` and
    `InvalidAlgorithmParameterException` as an authentication failure.
  - `repository/ArtefactAuthenticator.kt:97` maps that to
    `ArtefactCheck.Corrupt`.
  - `SourceViewRepository.kt:62-65` and `DuplicateVerifier.kt:47-50`
    then set the row to CORRUPT.
  - `RecoveryRepository.verifyAll` only checks READY rows
    (`RecoveryRepository.kt:136`), so no path ever returns a row to
    READY.
  - At bootstrap, `VaultBootstrapper.isTemporaryKeystoreFailure`
    (`storage/VaultBootstrapper.kt:165-176`) looks for
    `java.security.KeyStoreException`, but the cause here would be
    `android.security.KeyStoreException`. The failure therefore becomes
    the non-retryable `KEY_UNWRAP_FAILED`.
- **Scenario:** a keymaster hiccup at `doFinal` while the user opens an
  item. The row is marked CORRUPT and the user is offered "delete" for an
  intact original. If the hiccup happens at startup, the whole vault shows
  as unrecoverable.
- **Confirmed:** the mapping chain above.
- **Suspected:** that AndroidKeyStore reports unknown keymaster errors at
  `doFinal` as `IllegalBlockSizeException` with a KeyStoreException cause.
  Check AOSP `AndroidKeyStoreCipherSpiBase.engineDoFinal`.
- **Fix:**
  - Count only `AEADBadTagException` as authentication failure; map
    everything else to `KeystoreUnavailableException`.
  - Add a CORRUPT→READY path when a later deep check passes.

### V-02 (High): a missing database file plus a present key file deletes every blob

- **Where:**
  - `VaultBootstrapper.kt:83`: with a key file present, bootstrap reuses
    the key, and Room creates a fresh empty database.
  - `RecoveryRepository.kt:107-109, 241-254`: with zero rows,
    `allRowsReconciled` is true, so every `.blob` file is deleted as an
    orphan.
- **Scenario:** the database file is lost or removed while `database.key`
  and the artefacts survive. Recovery destroys every saved original. This
  breaks §11: "never infer that an unreadable database is empty and delete
  its files."
- **Confirmed.**
- **Fix:** if a key file exists, the database file is absent and
  `artefacts/` is not empty, return Unavailable with a new
  `DATABASE_MISSING` cause.

### V-03 (Medium): Save releases the import slot even when the stage is kept

- **Where:** `repository/ImportRepository.kt:370-374` always calls
  `importSlot.release`, including on `Failed` from a transient
  authentication failure (line 328) or an unverifiable duplicate check
  (line 338).
- **Scenario:** Save fails on a Keystore or I/O glitch. The row stays
  STAGED but the slot is free. The next MainActivity foreground runs
  `cleanAbandonedStages`, which deletes the stage, and a second import can
  start meanwhile. C0-R16 says a transient failure keeps the stage.
- **Confirmed** in the vault code. Whether the UI still offers Save at
  that point is suspected.
- **Fix:** release the slot only once the row has left STAGED or the
  stage has been removed.

### V-04 (Medium): a slow provider read freezes viewers, deletion and OCR

- **Where:**
  - `ImportRepository.kt:92`: `prepareImport` holds a read lease for the
    whole provider read, up to 15 s plus a 5 s grace period.
  - `MutationQueue.kt:53-56`: a waiting mutation holds the gate, so every
    new reader blocks behind it.
  - `MutationQueue.kt:62-70`: `tryMutation` takes the gate and then waits
    for readers.
  - `RecoveryRepository.kt:207-213`: `cleanAbandonedStages` checks
    `isPreparing` only after that wait.
- **Scenario:** while a slow cloud provider is being read, any queued
  delete, OCR commit or foreground cleanup pass blocks every viewer read
  and thumbnail for up to 20 s. C0-R12 says the foreground pass "skips"
  in this case.
- **Confirmed.**
- **Fix:**
  - Read the provider stream before taking the lease. Hold the lease only
    for the STAGED row insert, the stage write and the row update.
  - Make `tryMutation` give up, rather than wait, when readers are active.

### V-05 (Medium): ML Kit is still linked and registered, and its evidence boxes are wrong

- **Where:**
  - `vault/build.gradle.kts:83` still links the dependency, and
    `app/.../OpenLifeApp.kt:267` still registers the engine. Both go
    against P1 and decision 0003.
  - `ocr/MlKitOcrEngine.kt:43-50, 79-82`: scaled rectangles are clamped
    to the downsampled bitmap's width and height instead of the source
    display size.
- **Scenario:**
  - A telemetry-carrying, closed-source SDK ships in every build.
  - If the engine were selected, evidence boxes on images over 4 MP would
    be clamped into the top-left corner.
- **Confirmed.** C1.md still describes ML Kit coordinates, so the spec has
  drifted too.
- **Fix:** delete the engine and the dependency rather than fix them. Move
  `OcrUnsupportedScriptException`, which Tesseract uses, out of
  `MlKitOcrEngine.kt` first.

### V-06 (Medium): the golden envelope fixture pins the wrong AAD

- **Where:** `vault/src/test/.../crypto/GoldenEnvelopeFixtureTest.kt:33`
  uses `EnvelopeAad.forDomain`. Every artefact and wrapped DEK on disk
  uses `forSource`, which appends the UUID.
- **Scenario:** a change to how the UUID bytes are encoded in the AAD
  passes every round-trip test and makes every saved blob unreadable.
- **Confirmed.**
- **Fix:** add a `forSource` golden fixture with a fixed UUID.

### V-07 (Low): OCR reports a transient failure as SOURCE_CORRUPT

- **Where:** `repository/OcrRepository.kt:85-86` treats any null from
  `decryptAndVerify` as `SOURCE_CORRUPT`, including a Keystore or I/O
  failure.
- **Scenario:** a Keystore hiccup tells the user their source is corrupt.
- **Confirmed.**
- **Fix:** use `authenticator.check()` and map Transient to a retryable
  reason.

### V-08 (Low): `catch (_: Exception)` swallows `CancellationException`

- **Where:** `ImportRepository.kt:361` and `DeletionRepository.kt:82`.
- **Scenario:** the READY commit succeeds, but cancellation arrives as the
  call resumes. Save then reports Failed for a saved item. Cooperative
  cancellation is also broken.
- **Confirmed.**
- **Fix:** rethrow `CancellationException` before the generic catch.

### V-09 (Low): the wrapped key file is written in place

- **Where:** `storage/VaultKeyFile.kt:31-34` calls `Fsync.writeAndSync`
  directly on `database.key`, with no temp file and rename.
- **Scenario:** a crash during the first-run key write leaves a torn file.
  Bootstrap then reports the non-retryable `KEY_FILE_CORRUPT`, with no
  database or data at stake, and only Reset gets out of it.
- **Confirmed.**
- **Fix:** write a temp file, sync it, rename it into place, then sync the
  directory. Alternatively, treat a corrupt key with no database and empty
  artefacts as a fresh install.

### V-10 (Low): vault reset is brittle

- **Where:**
  - `repository/VaultResetRepository.kt:68`: any stray file in
    `artefacts/` whose name is not a UUID makes reset fail every time,
    with the marker set, so the vault stays unavailable.
  - `VaultResetRepository.kt:73-83`: reset never deletes `-journal`, which
    Room uses in TRUNCATE mode on low-RAM devices.
- **Confirmed.**
- **Fix:** delete or ignore unknown files under the vault directory during
  reset, since reset is explicit and confirmed. Also delete `-journal`.

### V-11 (Low): READY-invariant triggers exist only on fresh create

- **Where:** `storage/OpenLifeDatabase.kt:33-58` creates the triggers in
  `onCreate`. The v1→v2 migration keeps them, but nothing re-creates them.
- **Scenario:** a future migration that rebuilds the `sources` table
  silently drops the invariant.
- **Confirmed.**
- **Fix:** move the trigger DDL into a shared function. Call it from every
  migration that touches `sources`, and assert the triggers exist in the
  migration test.

### V-12 (Low): the fault-injection seam does not reach recovery

- **Where:** `RecoveryRepository.kt:233` hard-codes
  `ArtefactFileOps.Default`.
- **Confirmed.**
- **Fix:** inject `ArtefactFileOps` as the other repositories already do.

### V-13 (Low): no real process-kill checkpoint tests

- **Where:** the androidTest checkpoint coverage (for example
  `ImportRepositoryTest`, `RecoveryRepositoryTest`) is in-process fault
  injection.
- C0-09 asks for the process to be killed at every persistence
  checkpoint; that is not done.
- **Confirmed.**
- **Fix:** add kill-based checkpoints, for example a test-only hook that
  calls `Process.killProcess` at named checkpoints, with recovery asserted
  on relaunch.

### V-14 (Low): over-engineered schema-version test

- **Where:** `Vault.kt` (`SCHEMA_VERSION`) exists only so
  `VaultTest.kt`, a 138-line class-file parser, can assert it.
- **Confirmed.**
- **Fix:** delete both. Room's exported schema files and the migration
  tests already pin the version.

### V-15 (Low): one non-Latin letter fails the whole OCR revision

- **Where:** `ocr/OcrRunStateMachine.kt` (`decideOutput`) and
  `ocr/TesseractOcrEngine.kt` (`extract`).
- **Scenario:** a single stray non-Latin glyph on a receipt discards every
  line of the revision. This is defensible under C1's wording but brittle.
- **Confirmed.**
- **Fix:** see VS-3. Record script status per span instead.

### V-16 (Suspected): a READY commit could be lost on power failure

- **Where:** SQLCipher WAL sync mode, which was not checked.
- **Scenario:** if the mode is NORMAL, power loss after "Saved on this
  device" can roll the READY commit back. Recovery would then treat the
  row as STAGED and delete its blob.
- **Fix:** check the effective `PRAGMA synchronous`. Set FULL explicitly
  for the vault database.

### Ruled out

- **Recovery racing a cold-start share:** `recover()` finishes before
  `ImportRepository` is constructed (`app/.../OpenLifeApp.kt:124-130`).
- **OCR continuing after deletion:**
  - The app cancels the in-flight run when a source leaves the list
    (`app/.../ui/OcrViewModel.kt:41`).
  - `OcrRepository.persist` turns a late result into Stale.
  - The vault itself does not own that cancellation.

## Structural recommendations before facts and records

### VS-1: make state transitions explicit and transactional

- Replace full-row `@Update` of stale snapshots with compare-and-set
  updates (`UPDATE … SET state=? WHERE id=? AND state=?`).
- Store enums with a fallback instead of `valueOf`, which crashes on an
  unknown value.
- Facts and records will multiply the writers that today rely only on
  mutation exclusion.

### VS-2: route deletion and stale marking through one dependency traversal

- Add an explicit dependency-edge table before C2.
- Use RESTRICT on cross-source edges, and keep CASCADE only for children
  that a single source owns.
- CASCADE plus a hand-written `deleteForSource` can't express "invalidate
  a record that also depends on a retained source", and can't purge FTS
  rows.
- Index FTS5 through triggers so deletion reaches it.

### VS-3: give evidence and corrections exact, explicit references

- Corrections should supersede each other through an explicit link, not
  "latest `createdAt`".
- Add an explicit current-revision pointer. Today several revisions can be
  ACCEPTED, and STALE has two meanings (process restart and source
  deleted).
- Persist per span whether it has an evidence region, and its script
  status. Accepting a revision currently accepts its ungrounded spans too.
- Add a nullable `page` column to spans before C9.
