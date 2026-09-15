# Capability 0 security self-review

**This is a self-review by the author of the implementation, not an
independent audit.** Per design §13: "Security and provenance reviews must
inspect the actual implementation, not just approve this document." This
review re-derives each claim in `docs/THREAT_MODEL.md` against the actual
code, manifest, dependency graph, and test evidence in this repository as of
commit `abbcf46` (2026-09-15), rather than restating the design document's
intentions. Findings that required a code change are already fixed and
committed; findings that remain open are listed under "Open items," not
silently dropped.

## Method

For each row of the C0 threat matrix, the actual defence was located in code
and cross-referenced against `docs/verification/C0.md`'s evidence for that
row. Where a claim could be checked directly (a grep, a manifest inspection,
a dependency list) that check was re-run for this review rather than trusted
from memory of earlier stages.

## Threat matrix, re-verified

### Another ordinary app seeks vault content

- Private storage: `VaultPaths.vaultDir = File(context.noBackupFilesDir, "vault")`
  (`vault/src/main/java/org/openlife/vault/storage/VaultPaths.kt`) — confirmed
  on-device (C0-13) as mode `700`/`600`, credential-protected app-private
  storage.
- No exported vault reader: the release merged manifest (re-inspected for
  C0-01, 2026-09-14) exports exactly `MainActivity` (launcher, required) and
  `IntakeActivity` (the one intentional `ACTION_SEND` surface). No
  `ContentProvider`, `Service`, or `BroadcastReceiver` in `app/src/main` is
  exported. `androidx.startup.InitializationProvider` (AndroidX plumbing,
  `exported=false`) and `androidx.room.MultiInstanceInvalidationService`
  (`exported=false`) are the only other declared components; both are
  standard AndroidX defaults, not application-specific.
- Narrow incoming URI grant: `IntakeIntentValidator` rejects every intent
  shape except a single `content://` URI via `ACTION_SEND`, with no durable
  grant retained (`grep -rn takePersistableUriPermission app/src/main`
  returns nothing).
- **Confirmed, no changes needed.**

### Malicious or misleading shared image

- Explicit preview: `IntakeUiState.Preview` shows the sampled decode of the
  authenticated *stage* file, never a second read of the original provider
  (design §11; verified by `IntakeActivityTest.changedBytesAfterOpenAreNotSilentlySubstituted`).
- Strict type/size checks: `ImageHeaderValidator`, `BoundedStreamReader`,
  `ImportLimits` (16 tests in `ImageHeaderValidatorTest`, `:vault:test`).
- Inert display: the image is decoded through `BitmapFactory`/Compose
  `Image`, never through a WebView or any renderer capable of executing
  active content; no such renderer exists anywhere in the dependency graph
  (C0-01's dependency inventory).
- **Confirmed, no changes needed.**

### Copied storage or accidental backup

- Encrypted artefacts/database: verified byte-for-byte opaque on-device
  (C0-13) — no JPEG magic bytes, no JFIF/EXIF strings, SQLCipher database
  doesn't expose even its own plaintext header.
- Keystore wrapping: `KeystoreWrapper`, non-exportable AES-256-GCM key,
  provider-generated IVs only (`KeystoreWrapperTest`, 6/6).
- Backup exclusions: `backup_rules.xml`/`data_extraction_rules.xml` exclude
  every domain from both legacy and Android 12+ paths (re-inspected for
  C0-13); `allowBackup=false` confirmed in the actual merged manifest, not
  just the source XML.
- **Residual limit holds as documented**: file count and ciphertext sizes
  remain observable to anyone with filesystem access to the (still
  credential-protected) app directory. This is stated, not hidden.

### Corruption or interrupted writes

- Authenticated encryption: `AesGcmCodec`/`EnvelopeCodec`, exhaustively
  tamper-tested (`AesGcmCodecTest` 13/13, `EnvelopeCodecTest` 11/11) and
  proven end-to-end through the real repository/viewer path this stage
  (`EnvelopeTamperingThroughRepositoryTest`, 3/3).
- Staged commit + deterministic recovery: `RecoveryRepository`, exercised
  against every constructed interrupted state (`RecoveryRepositoryTest`,
  10/10) *and* against four genuine `kill -9`-mid-write real process kills
  this stage (C0-09), each followed by clean, idempotent self-healing with
  no accumulation and no `FATAL EXCEPTION`.
- Failed cancellation cleanup: `ImportRepositoryTest.cancellingAStagedImportRetainsTheRowWhenStageCleanupFails`
  (included in the focused 11/11 and full 46-test vault connected runs) proves
  a blocked stage path leaves the STAGED row available for a later retry.
- Final commit rejection: `ImportRepositoryTest.saveCommitFailureReturnsFailedAndLeavesRenamedArtefactRecoverable`
  covers a post-rename database rejection; `saveImport` returns failure and
  leaves durable STAGED ownership for recovery.
- Provider and rename failures: `providerReadFailureReturnsFailedAndCleansStagedRow`
  and `renameFailureReturnsFailedAndLeavesTheStageForRecovery` cover cleanup
  after an input read error and preservation of the stage when the final blob
  rename is rejected.
- Write and directory-sync failures: `stageWriteFailureReturnsFailedAndCleansStagedRow`
  and `directorySyncFailureReturnsFailedAndLeavesRenamedArtefactRecoverable`
  use the narrow `ArtefactFileOps` seam; production still delegates to the
  real `Fsync` implementation, while tests force the exact failure boundaries.
- **Confirmed, strengthened this stage** (real kills, not only constructed
  states).

### Screenshot or shoulder surfing

- `FLAG_SECURE` on every content-bearing window: confirmed via
  `dumpsys window org.openlife` showing `fl=... SECURE ...` on both
  `MainActivity` and `IntakeActivity` (C0-15, re-verified 2026-09-14), and
  via `adb screencap` returning a black image.
- Protected recents: **this is the same `FLAG_SECURE` mechanism**, not a
  separate control — `dumpsys activity recents` shows no custom
  `taskDescription` bitmap for the OpenLife task, consistent with the OS
  suppressing the recents-switcher thumbnail for a secure window. The threat
  model's "Protected recents" and "Secure windows" bullets are, in this
  implementation, one control (`applySecureWindow()`), not two independent
  ones — worth noting so a future reader doesn't look for separate recents-
  clearing code that doesn't exist. Explicit lifecycle scrubbing is a
  separate control now: `MainActivity.onStop()` clears decoded thumbnails and
  returns the viewer to the list, while `IntakeActivity.onStop()` drops
  authenticated preview state and re-authenticates its staged UUID on return.
  `DisposableEffect` releases decoded viewer/preview bitmaps, and the
  generation barrier prevents an in-flight thumbnail decode from repopulating
  the cache after backgrounding.
- No content notifications: confirmed — `grep -rn Notification app/src/main
  vault/src/main` returns nothing; the app never posts a notification at
  all in C0.
- **Background scrubbing is explicit and covered by the new lifecycle/cache
  regression tests**; no plaintext state is intentionally retained by the
  app-owned UI after `onStop()`.
- Accessibility (TalkBack) "preserved and tested": **partially
  unsubstantiated.** Every icon-only control carries a `contentDescription`
  (re-verified this stage), and large-text rendering was checked at 1.3×/2.0×
  scale with no clipping (C0-16). But no TalkBack accessibility-service run
  was actually performed in this environment — "tested" overstates what
  Stage 8 could verify here. `THREAT_MODEL.md` now states this as an
  unverified gap, matching `docs/verification/C0.md`'s precise "partial"
  status for C0-16.

### Compromised dependency or release

- Minimal dependency set: confirmed this stage (C0-01) — every dependency in
  `releaseRuntimeClasspath` is AndroidX/Compose/Kotlin/Room/SQLCipher; zero
  analytics, ads, crash-reporting, or networking libraries.
- Locked artefacts: `gradle/libs.versions.toml` pins every version
  explicitly; no dynamic (`+`) version ranges anywhere
  (`grep -rn "[0-9]\+\.+" gradle/libs.versions.toml` — none found).
- Manifest/egress checks: this review's own re-run of C0-01, above.
- **Residual limit holds as documented**: source availability doesn't prove
  binary integrity (no reproducible-build or signing-provenance claim is
  made, correctly).

### Resource exhaustion by a provider

- Byte/pixel ceilings: `ImportLimits` (16 MiB, 40M pixels, 16384 longest
  edge, 4M preview pixels), enforced before any unbounded allocation
  (`ImageHeaderValidatorTest`).
- Bounded preview: sampled decode only, verified no full-size bitmap
  allocation spike in this stage's own memory measurement (138,971 KB PSS
  total after displaying a real 3.8 MB image — see the Performance section
  of `docs/verification/C0.md`).
- Cancellable I/O: the 15-second cooperative-cancellation deadline
  (`ImportLimits.PROVIDER_READ_DEADLINE_SECONDS`) — **found and fixed a real
  gap in this control this stage (C0-17)**: it only ever covered *reading*
  an already-opened stream, never the `contentResolver.openInputStream()`
  call itself, which ran directly on the main thread and produced a genuine
  Android ANR under a slow provider. Fixed by moving that call to
  `Dispatchers.IO`; regression-tested
  (`IntakeActivityTest.slowProviderOpenStaysResponsiveInsteadOfFreezingTheActivity`).
  The design's own caveat ("not a guaranteed hard stop for a blocked
  provider or native decoder in the same process") still applies to the
  *decode* step specifically, which was not separately re-verified this
  stage beyond the existing corrupt/malformed-input tests.

## Open items

1. ~~"Visible content is cleared when the app backgrounds" had no
   corresponding app code~~ — **fixed in `d497234`** with explicit lifecycle
   scrubbing and a red/green cache regression test.
2. ~~TalkBack "tested" overstated actual coverage~~ — **fixed**:
   `THREAT_MODEL.md` now states plainly that an actual TalkBack
   accessibility-service run has not been performed in this environment.
3. All findings from `docs/verification/C0.md`'s own "Known environment
   limitations" section apply equally here (no physical device, no API 29
   image, device-transfer/backup unverifiable on emulator) and are not
   repeated in full.

No release-blocking security or data-loss defect was found that isn't
already reflected as a fix in this stage's commits (C0-09/C0-10's real
process-kill self-healing, C0-11's end-to-end tamper-fails-closed proof, and
C0-17's ANR fix). The two open items above are documentation-accuracy
findings, not implementation defects.
