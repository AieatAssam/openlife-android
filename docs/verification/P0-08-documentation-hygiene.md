# P0-08 verification record

Date: 2026-09-17
Branch: `plan/P0-08-documentation-hygiene`

## TDD and completed checks

- RED commit `8603b6a`: `VaultTest.schemaVersionMatchesRoomDatabaseVersion`
  failed for the intended missing runtime annotation access, and the new
  source boundary reported the remaining implementation-stage comments. The
  existing plan-status fixture assertions also exposed stale expectations;
  those fixtures were corrected without changing plan-status behaviour.
- GREEN commit `b5f5d04`: source comments, public-repository documents,
  backup wording, stale verification references, release workflow wording, and
  debug-only recovery logging are corrected.
- `bash scripts/tests/run.sh` — `44 passed, 0 failed`; the shipped-source
  stage-comment grep is empty.
- `./gradlew :vault:test` — `BUILD SUCCESSFUL`.
- `./gradlew detekt lint :app:test :vault:test` — `BUILD SUCCESSFUL`; Detekt
  reported zero issues, lint passed, and the app/vault JVM suites completed
  25/25 and 65/65 tests.
- `git diff --check` — clean.

## Instrumented-test gap

The connected suites were attempted for the preceding Compose/receiver work
and again remain dependent on the unavailable local device. The exact command
was:

```text
./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

The final-tree rerun reached both connected tasks and failed each with
`DeviceException: No connected devices!`. An earlier bounded attempt with the
available `dev36` AVD crashed during startup once and stayed
`emulator-5554 offline` during a software-rendered retry. No passing
instrumented result is claimed. CI API 29/API 36 execution remains required.
