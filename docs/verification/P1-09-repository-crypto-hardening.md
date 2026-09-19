# P1-09 repository and crypto hardening

Verification record updated on 2026-09-19 UTC at consolidated commit `00d93d2`
on
`plan/P1-09-repository-crypto-hardening`.

## Automated results

- `./gradlew detekt :vault:test :app:test` — `BUILD SUCCESSFUL`; Detekt
  reported no findings; vault JVM tests: 68 passed, 0 failures/errors/skips;
  app JVM tests: 33 passed, 0 failures/errors/skips.
- `:vault:compileDebugAndroidTestKotlin` and
  `:app:compileDebugAndroidTestKotlin` — `BUILD SUCCESSFUL`.
- `./gradlew :vault:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.crypto.KeystoreWrapperTest`
  — `BUILD SUCCESSFUL`; 6 tests passed, 0 failures/errors/skips on the
  API-36 `dev36` emulator.
- `./gradlew :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.IntakeAndListFlowTest`
  — `BUILD SUCCESSFUL`; 10 tests passed, 0 failures/errors/skips on the
  API-36 `dev36` emulator. This includes the provider deadline and
  owner-thread close assertion.
- Required command
  `./gradlew detekt :vault:test :app:test && ./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
  — JVM/static half passed and both debug test APKs packaged. In the final
  connected attempt the vault task advanced to the app task, then the
  emulator disappeared during app test-output collection/cleanup:
  `adb: device 'emulator-5554' not found` / `DeviceException: No connected
  devices!`. The command did not produce a reliable full-suite report, so no
  full-suite count is claimed. CI/device execution remains required for the
  complete suite and API-29 coverage.

## Coverage added by this step

- Sparse oversized artefact/key files are rejected before the injected file
  reader is called.
- `BoundedStreamReader` checks a deadline between 64 KiB reads and exposes a
  typed `ReadDeadlineExceededException`.
- Repository-owned import plaintext is zeroed after validation/encryption;
  authenticated boolean-only reads also zero decrypted bytes.
- Repository construction requires the composition-root `MutationQueue`.
- Intake stream ownership is exercised by the deadline androidTest; its
  descriptor-close assertion passed on the API-36 emulator.

## Hosted closure

Hosted run `35451868283` completed both connected legs after the intake wait
hardening and follow-up UI fixes:

- API 29: vault `72/72`, app `38/38`.
- API 36: vault `72/72`, app `38/38`.

The run uploaded the connected test results and filtered logcat artefacts.
This closes the previous local-emulator gap for the step's required connected
verification. The owner also reports a physical-device test; the separate
missing share-target observation remains an intake diagnostic outside the
repository/crypto acceptance criteria.

## CI evidence (2026-09-18)

Run 35394382065 on the step branch failed in both JVM jobs before any test
ran: Gradle dependency verification rejected 17 parent POM/BOM artefacts
that a warm local cache had never recorded into
`gradle/verification-metadata.xml`. Lint also failed on
`AndroidGradlePluginVersion` after AGP 9.4.1 was published upstream.
Commit 139f0d5 regenerated the metadata with `--refresh-dependencies`
across the full CI task set and added the network-driven plugin-version
check to both modules' ignored issues with a stated reason.

Run 35395910369 (same branch, push): `Build, lint, unit tests` success;
`Release APK boundary inspection` success; instrumented API 29/36 legs
skipped by design on a non-main branch push. Device legs run when the
pull request is opened.

## IntakeAndListFlowTest HOLD (2026-09-19)

Independent review at commit `3540eff` held the step because
`:app:connectedDebugAndroidTest` for `IntakeAndListFlowTest` was **7/10**
on a local API-33 software emulator (`-accel off`). The P1-09 deadline
case `readDeadlineProducesFailedStateAndReleasesTheDescriptorOnTheOwningThread`
**passed** (17.2s). These three timed out on a generic 30s `awaitCondition`:

- `cancellingAPreviewDiscardsItWithoutSaving`
- `listHoldsManySourcesWithoutLosingOrMisorderingAny`
- `restoringFromASavedStateHandleReAuthenticatesTheStagePreview`

This is not a length-bound / deadline-typing / zeroisation regression.
The same class already documents the process-wide `MutationQueue` isolation
rule (C0-17): `PrepareResult.Busy` is a **terminal** `IntakeUiState` when
`tryAcquire` loses, so polling for `Preview` for 30s cannot recover. Soft
emulators also make Keystore unwrap + SQLCipher path verification on
`loadStagePreviewBytes` slow enough that a 30s poll with no last-state
message is a poor signal.

Mitigation in `IntakeAndListFlowTest` (P1-09 hardening behaviour unchanged):

- `importUntilPreview` retries **once** after `Busy`, then fails with the
  last state
- unexpected terminal states fail immediately instead of waiting out the
  poll
- crypto/list waits are bounded at 60s and include `last state=...`
- the many-sources assertion requires the fifteen saved IDs to be present
  and unique among themselves; it no longer requires the shared vault to
  contain *only* those rows

Connected re-run of this class is still required before moving P1-09 to
done. FLAG_SECURE / UI timing is not involved: these cases drive
ViewModels directly.
