# P1-09 repository and crypto hardening

Verification run on 2026-09-17 UTC from commit `5649969` on
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
