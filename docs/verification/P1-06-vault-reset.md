# P1-06 verification: unavailable vault and explicit reset

Date: 2026-09-23
Branch: `plan/P1-06-reset-vault`
Device: `dev36` emulator, API 36, x86_64

## Automated evidence

- RED was committed as `77e0beb`. Before implementation, typed-cause mapping,
  reset completion/interruption/busy handling, and the unavailable UI
  assertions failed against the intended missing behaviour. The temporary
  Keystore failure retry assertion also failed before the retry flow existed.
- Focused RED-to-GREEN runs on `dev36` passed:
  - `VaultBootstrapperTest`: 6/6, including corrupt key, missing database key,
    unwrap failure, temporary Keystore failure, storage I/O, and reset marker.
  - `VaultResetRepositoryTest`: 3/3, including reset followed by fresh
    bootstrap, interruption recovery, and mutation-queue refusal.
  - `VaultUnavailableFlowTest`: 2/2, including cause-specific copy, no direct
    reset action, typed two-stage confirmation, and retry after a temporary
    Keystore failure.
- Required command
  `./gradlew detekt :vault:test :app:test --no-configuration-cache`:
  **BUILD SUCCESSFUL**; Detekt passed, vault JVM 71/71 and app JVM 43/43,
  failures=0, errors=0, skipped=0.
- Required connected command
  `./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest --no-configuration-cache`:
  **BUILD SUCCESSFUL** on `dev36` API 36; vault 82/82 and app 47/47,
  failures=0, errors=0, skipped=0. The app count includes both
  `VaultUnavailableFlowTest` cases. The vault module was rerun separately with
  `--rerun-tasks` after its first combined-run report showed zero discovered
  tests; the fresh run produced all 82 passing results.
- After the About screen's configuration-aware resource refactor,
  `./gradlew detekt :vault:test :app:test --no-configuration-cache` passed,
  and `./gradlew :app:connectedDebugAndroidTest --no-configuration-cache`
  passed 47/47 on `dev36` API 36.
- `scripts/plan-check.sh`: `steps=77 errors=0`.

## Repository-wide gate

`./gradlew detekt lint :app:test :vault:test assembleDebug --no-configuration-cache`
was run and reaches lint. The `LocalContextResourcesRead` finding in the
About screen was corrected by using configuration-aware `LocalResources`.
Lint now fails on two existing findings outside this step:

- `strings.xml:92`: `PluralsCandidate` for `viewer_display_rotation`
- `strings.xml:111`: `UnusedResources` for `about_title`

The remaining findings are present in unchanged resource entries and are
recorded as a repository gate gap. Because lint fails, the chained
`assembleDebug` task does not run in that gate.

## Manual verification

**Gap — not run:** the prescribed manual sequence (`run-as` + `dd` corruption
of `database.key`, launch and inspect the unavailable copy, then use Settings
to reset and import into the fresh vault) was not performed. Automated device
coverage exercises the corrupt-key screen, the two-step confirmation, the
reset deletion and interruption paths, and successful fresh bootstrap, but it
does not replace this full manual transcript.

No API 29 or physical-device run is claimed. The available connected device
was the API 36 emulator.
