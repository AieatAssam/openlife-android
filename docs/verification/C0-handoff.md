# Capability 0 handoff

Per design §13: "The handoff must contain the build identifier, pinned
dependencies, device/API matrix, test results, merged-manifest report,
storage and backup observations, checkpoint recovery results, known
limitations and reviewer decisions." This document assembles those into one
place; the underlying evidence lives in `docs/verification/C0.md` and
`docs/reviews/`, which this file indexes rather than duplicates.

## Build identifier

- Commit: `bd43e8e` (2026-09-15; deletion checkpoint fault-injection coverage)
- `applicationId` `org.openlife`, `versionCode` 1, `versionName` 0.0.1-c0
- Gradle 9.7.1, AGP 9.4.0, Kotlin 2.4.10 (pinned in `gradle/libs.versions.toml`,
  no dynamic version ranges anywhere in that file)
- `net.zetetic:sqlcipher-android` 4.19.0, Room 2.8.5

## Device/API matrix

- `dev36` AVD: Pixel 7 profile, API 36 (`google_apis`), x86_64, GPU
  passthrough (`-gpu host -feature -Vulkan`, see `CLAUDE.md`)
- `minSdk` 29 declared; **no API 29 system image was installable in this
  environment** (read-only Nix-store `$ANDROID_HOME`) — recorded as a gap,
  not silently assumed to pass. See `docs/decisions/0001-c0-defaults.md`
  item 4.
- No physical device available at any point in this project.

## Test results

| Suite | Count | Result |
| --- | --- | --- |
| `:app:test` + `:vault:test` (JVM unit) | — | all pass on the current tree (`./gradlew :app:test :vault:test assembleDebug lint`, 2026-09-15) |
| `:app:connectedDebugAndroidTest` | 20 (prior code) | all pass on `dev36` for `a194b38` (IntakeActivity 8/8, IntakeAndListFlow 9/9, DeletionRetryFlow 1/1, IntakeScreen 1/1, SourceListScreen 1/1); the new duplicate-choice test is not yet device-verified |
| `:vault:connectedDebugAndroidTest` | 46 (prior code) | all pass on `dev36` for `a194b38`; the new ImportRepository tests are compiled but their focused connected run was blocked first by another project taking the shared emulator and then by a fresh isolated AVD hanging in the package service during streamed APK installation |
| `:app:assembleDebug` / `lint` | — | passed on the current tree on 2026-09-15 |

The JVM suites, build/lint sweep, and vault connected suite were re-run on the
current tree on 2026-09-14. The final app connected run passed all 20 tests on
`dev36`; earlier package-manager/process instability is retained in the
environment limitations rather than used to downgrade this completed run.

## Requirement-to-test traceability

`docs/capabilities/C0.md` maps every `C0-R<n>` requirement to a
verification-matrix row (`C0-01`…`C0-17`); this table goes one level deeper,
to the actual test method(s), for every requirement whose evidence changed
or was added during Stage 8. Requirements not listed here are unchanged from
`docs/capabilities/C0.md`'s existing mapping and `docs/verification/C0.md`'s
per-row evidence (already cites concrete test names for every row).

| Requirement | Test(s) | Note |
| --- | --- | --- |
| C0-R9 (Saved only after READY commit) | `EnvelopeTamperingThroughRepositoryTest.anUntamperedSourceStillRoundTripsAfterTheAboveChecks`; real kill-during-Save attempts (C0-09/C0-10) | Kill-during-Save could not be externally interrupted mid-rename — recorded as a negative result, not silently dropped |
| C0-R11 (rotation via ViewModel/UUID only) | `IntakeAndListFlowTest.restoringFromASavedStateHandleReAuthenticatesTheStagePreview` | Pre-existing; the fix this stage was to the *test's own* cross-test hygiene (awaiting `Cancelled`), not this requirement's evidence |
| C0-R14 (no plaintext/thumbnail/URI persisted) | On-device `od`/`strings` inspection of `.blob`, `openlife.db`, `.db-wal`, `database.key` (C0-13) | Found and fixed a real (debug-only) hygiene gap: a leftover cache temp file from `TestHostileContentProvider`, unrelated to production code |
| C0-R19/C0-R20 (envelope binding, authenticate-before-decode) | `EnvelopeTamperingThroughRepositoryTest` (3/3) | New this stage — proves the codec-level guarantees (`AesGcmCodecTest`/`EnvelopeCodecTest`) hold through the real repository/viewer path, not only in isolation |
| C0-R12/C0-R22/C0-R23 (durable staged cleanup and idempotent recovery) | `ImportRepositoryTest.cancellingAStagedImportRetainsTheRowWhenStageCleanupFails`; `RecoveryRepositoryTest` (constructed states) + 4 real `kill -9` mid-write injections (C0-09) | Failed cancellation retains the STAGED row for retry; real kills landed confirmed-mid-write (distinct fresh `.stage` file each time); every relaunch logged `RecoveryReport(cleanedStaged=1, ...)` with no accumulation |
| C0-R29 (one concurrent import) | `MutationQueueTest`; incidentally re-proven by the Stage 7 cross-test hang investigation, where `tryAcquire` correctly returning "busy" was shown to work exactly as designed | The hang that investigation resolved was a test-isolation bug, not a defect in this requirement's implementation |
| C0-R30 (15s provider-read deadline) | `BoundedStreamReaderTest.closingTheStreamFromAnotherThreadUnblocksABlockedRead`; `IntakeActivityTest.slowProviderOpenStaysResponsiveInsteadOfFreezingTheActivity` | **Found and fixed a real gap this stage**: the deadline covered reading an opened stream but not opening it — see below |
| C0-R31 (no INTERNET/exported reader/telemetry, any variant) | Re-run C0-01 dependency inventory + full manifest diff (debug vs. release), 2026-09-14 | Confirmed against the *complete* app, not the Stage 0 placeholder this row was originally checked against |
| C0-R8 (sampled authenticated preview and Save gating) | `IntakeAndListFlowTest.sampledPreviewDecoderStaysWithinPixelBudget`; `IntakeScreenTest.saveIsUnavailableWhenAuthenticatedPreviewCannotBeLoaded`; ViewModel `confirmSave` guard | Red/green coverage added; included in the final 20/20 app connected run |
| C0-R24 (DELETING durability, retry, and view serialization) | `DeletionRepositoryTest` (7 cases, including `aFailureAfterTheFirstDeleteKeepsDeletingStateForRetry`); `RecoveryRepositoryTest` (10 cases); `DeletionRetryFlowTest.failedDeletionRemainsVisibleUntilRetrySucceeds`; `EnvelopeTamperingThroughRepositoryTest.viewerReadSharesTheMutationQueueWithDeletionWork` | A one-shot fault after the first file-removal checkpoint leaves the durable `DELETING` row/blob and a retry completes it; failed cleanup remains visible and retryable, and authenticated viewer reads share the mutation queue with deletion. The new connected execution remains pending the emulator/package-service gap. |
| C0-R32 (FLAG_SECURE, explicit background scrubbing, no content in logs/recents) | Full real lifecycle/logcat evidence (C0-15); `SensitiveContentCacheTest`; `IntakeAndListFlowTest.backgroundScrubsPreviewAndForegroundReauthenticatesIt` | Lifecycle/cache coverage is included in the final 20/20 app connected run |
| C0-R33 (large text, TalkBack) | Manual `uiautomator` walk at 1.3x/2.0x font scale across every screen (C0-16) | TalkBack itself remains unverified in this environment — stated as a gap in both `docs/verification/C0.md` and the security self-review, not claimed as passing |
| C0-R5 (own-authority trust boundary) | `IntakeIntentValidatorTest.ownAuthorityMatchIsCaseInsensitive` | JVM regression passes on the current tree; it rejects case variants of the app authority before any provider open |
| C0-R16 (duplicate choice) | `IntakeScreenTest.duplicateOffersOpenExistingOrCancel`; `MainActivity.EXTRA_OPEN_SOURCE_ID` route | UI and navigation are implemented; connected execution is pending the emulator gap recorded above |
| C0-R21 (final READY commit failure) | `ImportRepositoryTest.saveCommitFailureReturnsFailedAndLeavesRenamedArtefactRecoverable` | Repository now returns failure and retains durable STAGED ownership after a post-rename DB rejection; connected execution is pending the emulator gap recorded above |
| C0-R10/C0-R15 (provider and rename failure ownership) | `ImportRepositoryTest.providerReadFailureReturnsFailedAndCleansStagedRow`; `ImportRepositoryTest.renameFailureReturnsFailedAndLeavesTheStageForRecovery` | New repository regressions preserve cleanup/recovery ownership for read and filesystem rename failures; connected execution is pending the emulator gap recorded above |
| C0-R10/C0-R21 (write/sync failure ownership) | `ImportRepositoryTest.stageWriteFailureReturnsFailedAndCleansStagedRow`; `ImportRepositoryTest.directorySyncFailureReturnsFailedAndLeavesRenamedArtefactRecoverable` | `ArtefactFileOps` injects only the tested durability boundary; the production default still uses `Fsync`; connected execution is pending the emulator gap recorded above |
| C0-R1/C0-R2 (JPEG and PNG prepare paths) | `ImportRepositoryTest.validJpegIsPreparedAndStagedRowMatchesTheOriginal`; `ImportRepositoryTest.validPngIsPreparedAndStagedRowMatchesTheOriginal` | New platform-encoded PNG success path is compiled; connected execution is pending the emulator gap recorded above |
| C0-R26 (provider-independent byte ceiling) | `ImportRepositoryTest.excessBytesAreRejectedAndLeaveNoStagedState` | Streams limit+1 synthetic bytes through the real repository and asserts row/artefact cleanup; connected execution is pending the emulator gap recorded above |

## Real bugs found and fixed during Stage 8 (not merely constructed states)

1. **ANR from a slow content-provider open.** `IntakeActivity` called
   `contentResolver.openInputStream()` directly on the main thread with no
   timeout; a slow provider produced a genuine Android ANR
   ("Input dispatching timed out... Waited 5000ms"). Fixed by moving that
   call to `Dispatchers.IO`. This is the one finding in this stage that
   represents an actual defect in shipped app code, not test
   infrastructure or documentation — see `docs/verification/C0.md`'s C0-17
   row and the security self-review's "Resource exhaustion by a provider"
   section.
2. **Intake-boundary trust gaps.** The exported activity previously ignored
   the incoming read-grant flag and sender MIME declaration. It now requires
   `FLAG_GRANT_READ_URI_PERMISSION`, compares the sender and provider MIME
   types before opening bytes, and closes a stream if lifecycle cancellation
   happens before handoff.
3. **Unbounded UI preview and background retention.** Intake/viewer decode
   now samples to the 4M-pixel budget; Save is blocked if authenticated
   preview bytes cannot be loaded; lifecycle scrubbing clears preview bytes,
   decoded bitmaps, and thumbnail cache generations.
4. **Durable cleanup retry.** Recovery retains STAGED/DELETING rows when a
   file cannot be removed, the list exposes an unavailable retry state, and
   authenticated viewer reads serialize with deletion mutations. The
   cancellation path now also retains a STAGED row when its stage/blob cleanup
   fails, with a red/green retry regression.
5. **Transient intake errors disappeared before they could be acknowledged.**
   A slow first-run device/provider boundary could finish the exported
   activity before the rejection text was observed, and the user had no way
   to keep an error visible. Error, busy, and vault-unavailable states now
   remain on screen with an explicit Done action; only success and duplicate
   results auto-return.
6. A debug-only test fixture (`TestHostileContentProvider`) left served
   bytes in a `cacheDir` temp file indefinitely — confirmed not to affect
   any production code path, fixed anyway.
7. Two test-isolation bugs (a fire-and-forget `cancel()` coroutine racing
   the next test's mutex acquisition; byte-identical synthetic content
   across loop iterations accidentally exercising duplicate detection
   instead of the intended list-volume check) — both root-caused with live
   `adb shell debuggerd -b` thread dumps rather than guessed at, and both
   were bugs in test code, not app code.
8. Duplicate imports previously auto-dismissed without the required user
   choice; the UI now offers `Open existing` or `Cancel` and routes the
   existing UUID back to the viewer.
9. A database rejection after stage-to-blob rename previously escaped from
   `saveImport`; it now returns `Failed` while leaving the STAGED row and blob
   for recovery.

## Storage and backup observations

Summarized from C0-13 (`docs/verification/C0.md`): every vault file mode
`600`, every vault directory mode `700`; the on-disk `.blob` is fully opaque
ciphertext (zero JPEG magic-byte occurrences in a 12 MB file, no JFIF/EXIF
strings); the SQLCipher database doesn't expose even its own plaintext
header; the wrapped-key file is itself an envelope, never a raw key.
`backup_rules.xml`/`data_extraction_rules.xml` exclude every domain from
both legacy and Android 12+ backup/transfer paths. Device-transfer and
manufacturer-specific backup behavior remain unverifiable on this emulator
(known gap).

## Checkpoint recovery results

Summarized from C0-09/C0-10: `RecoveryRepositoryTest` (10/10) covers every
constructed interrupted state. Four **real** `kill -9`s landed confirmed
mid-write during Stage 8; every relaunch self-healed idempotently with no
accumulation, no false success, and no crash. Kill-during-Save could not be
externally interrupted (the rename+commit completes faster than this
environment's `adb` round-trip latency, and POSIX `rename()` is atomic by
construction) — recorded as a negative result rather than claimed covered.

## Known limitations

See `docs/verification/C0.md`'s "Known environment limitations" section in
full. Headline items: no physical device; no API 29 system image; TalkBack
itself not run under an actual accessibility service; device-transfer/backup
behavior unverifiable on an emulator; `pm clear` behaved like a full
uninstall at least twice during manual passes (worked around, didn't affect
automated runs).

## Reviewer decisions

- `docs/reviews/C0-security-self-review.md` — self-review (not independent
  audit, per design §13). Found and corrected two documentation-accuracy
  issues in `THREAT_MODEL.md`; found no unresolved release-blocking defect
  beyond the ANR already fixed above.
- `docs/reviews/C0-provenance-review.md` — confirms C0's provenance surface
  is exactly the design's Source-only model, with no fabricated Fact/Record/
  Action object anywhere in the codebase.

## Acceptance recommendation

The implementation gaps found in review are fixed and covered by red/green
regressions. C0 is not yet a release acceptance: the API-29, TalkBack,
physical-device/transfer checks, and the newly added device-level regressions
remain required design-gate evidence. Record those as explicit blockers rather
than calling them non-blocking. The code slice is ready for owner review; no
C1 implementation should begin until the remaining C0 evidence is accepted.

Per AGENTS.md and design §13 ("Do not begin C1 to fill the time"): this
stage stops here. The next step is proposing the C1 (Text provenance)
specification for owner acceptance — not implementing it speculatively.
