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

## Instrumented verification

The required command was rerun after the `dev36` AVD came online:

```text
./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

`BUILD SUCCESSFUL` in 5m 56s on `dev36` (API 36); the app connected suite
passed 28/28 and the vault connected suite passed 71/71, with zero failures,
errors, or skips. Earlier no-device and offline-emulator attempts are retained
in the prior handoff history, but are superseded by this successful run. CI
API 29/API 36 runs remain part of P0-04's matrix.
