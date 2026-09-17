# P1-09 repository and crypto hardening

Verification run on 2026-09-17 UTC from commit `1ff9ac9` on
`plan/P1-09-repository-crypto-hardening`.

## Automated results

- `./gradlew detekt :vault:test :app:test` — `BUILD SUCCESSFUL`; Detekt
  reported no findings; vault JVM tests: 68 passed, 0 failures/errors/skips;
  app JVM tests: 33 passed, 0 failures/errors/skips.
- `:vault:compileDebugAndroidTestKotlin` and
  `:app:compileDebugAndroidTestKotlin` — `BUILD SUCCESSFUL`.
- Required command
  `./gradlew detekt :vault:test :app:test && ./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
  — JVM/static half passed and both debug test APKs packaged, but the
  connected half stopped before execution with
  `DeviceException: No connected devices!`.
- `adb devices` — no devices listed. Therefore no connected test count or
  API-29/API-36 claim is made here; CI/device execution remains required.

## Coverage added by this step

- Sparse oversized artefact/key files are rejected before the injected file
  reader is called.
- `BoundedStreamReader` checks a deadline between 64 KiB reads and exposes a
  typed `ReadDeadlineExceededException`.
- Repository-owned import plaintext is zeroed after validation/encryption;
  authenticated boolean-only reads also zero decrypted bytes.
- Repository construction requires the composition-root `MutationQueue`.
- Intake stream ownership is exercised by the deadline androidTest; its
  descriptor-close assertion is compiled but not executed in this environment.
