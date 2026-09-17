# P0-03 verification record

Date: 2026-09-17
Branch: `plan/P0-03-static-analysis`

## Completed

- `./gradlew detekt lint :app:test :vault:test` — `BUILD SUCCESSFUL`; Detekt
  reported zero issues in `app` and `vault`, lint reported zero errors, and
  the app/vault JVM suites completed 25/65 tests with zero failures.
- `./gradlew detekt lint :app:test :vault:test assembleDebug` — `BUILD
  SUCCESSFUL`; 112 actionable tasks.
- `scripts/plan-check.sh` — `steps=77 errors=0`, runnable `[P0-08]`.
- `git diff --check` — clean.
- Acceptance mutation: replacing the manifest's `INTERNET` removal marker with
  a real permission made both `:app:test` and `:app:lint` fail
  `ManifestBoundaryTest.releaseManifestDeclaresNoNetworkOrBroadAccessPermission`.
  The mutation was reverted before handoff.

## Instrumented-test environment gap

The required command was attempted:

```text
./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

It reached packaging but failed at `:app:connectedDebugAndroidTest` with
`DeviceException: No connected devices!`; `adb devices` was empty. The local
`dev36` AVD was then started twice. The first launch exited 139 after QEMU
thread hangs; the software-rendered retry remained `emulator-5554 offline`
through the boot timeout and was stopped. No instrumented test result is
claimed. CI API 29/API 36 runs remain required.

