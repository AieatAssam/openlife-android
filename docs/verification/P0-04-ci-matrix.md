# P0-04 verification record

Date: 2026-09-17
Branch: `plan/P0-04-ci-matrix`

## TDD and local verification

- RED commit `5323109`: `scripts/tests/p0-04-ci-workflow.sh` failed against
  the existing workflow with `2 passed, 11 failed`; the intended gaps were
  JDK 17, API 34 only, unpinned actions, missing plan/static-analysis command,
  missing retry/artifact/release-inspection wiring, and absent KVM/logcat
  requirements.
- GREEN implementation adds the SHA-pinned JDK-21 workflow, API-29/API-36
  matrix, KVM emulator legs, assertion-aware one-retry wrapper, filtered
  logcat and 30-day artefacts, release inspection, and a CI contract test.
- REFACTOR commit `69b6d99` extracts the three-job JDK/cache setup into
  `.github/actions/gradle-setup/action.yml`; its third-party actions remain
  pinned by full SHA and the contract test covers the composite action.
- Review RED commit `ea6202c` added regression checks after review found a
  generic instrumentation `shortMsg` classifier and unpinned actions in the
  release workflow. Review GREEN commit `5ed757d` narrows the retry classifier
  to assertion-shaped output and pins the release workflow's checkout,
  reusable Gradle setup, artifact uploads, and GitHub Release action.
- Review RED commit `0a0a60c` added executable behavior checks for bare and
  parenthesized instrumentation failures. Review GREEN commit `628fae1`
  extracts `scripts/ci/connected-test-failure-is-assertion.sh`; synthetic
  infrastructure output is retryable while both assertion forms are
  terminal.
- `scripts/tests/p0-04-ci-workflow.sh` — `18 passed, 0 failed`.
- `bash scripts/tests/run.sh` — `45 passed, 0 failed`; `scripts/plan-check.sh`
  reports `steps=77 errors=0`.
- `./gradlew detekt lint :app:test :vault:test assembleDebug` — `BUILD
  SUCCESSFUL`, 114 actionable tasks; Detekt, lint, app/vault JVM tests, and
  debug assembly passed.
- `./gradlew assembleRelease` — `BUILD SUCCESSFUL` in 5m 59s; the build ran
  dependency verification, release dependency audit, SBOM/licence tasks, and
  produced the release APK.
- `scripts/inspect-release-apk.sh app/build/outputs/apk/release/app-release.apk`
  — `BOUNDARY_RESULT=pass`; package `org.openlife`, `allowBackup=false`, no
  forbidden permissions, exactly `MainActivity` and `IntakeActivity` exported,
  APK size `78,232,193` bytes.
- Acceptance mutation: changing the INTERNET manifest removal marker to merge
  and rebuilding the release APK made the inspector fail with
  `forbidden release permissions: uses-permission: name='android.permission.INTERNET'`;
  the mutation was reverted.
- `git diff --check` — clean after the implementation commit.

## CI evidence gap

The required command is:

```text
gh workflow run ci.yml --ref <branch> && gh run watch
```

The branch is not pushed and no authenticated GitHub run URL is available in
this workspace, so API-29/API-36 hosted-runner counts and artefact URLs are not
claimed. The workflow is configured for push, pull request, and manual runs;
the owner/CI environment must provide the hosted-run evidence.

Independent final review: James and Mill approved the implementation. The step
remains in `review` solely for the hosted-run evidence gap.

## Hosted instrumented setup failure (2026-09-19)

Run https://github.com/AieatAssam/openlife-android/actions/runs/35439433360
failed **both** API 29 and API 36 instrumented jobs in ~20s, before any
connected test started:

1. `reactivecircus/android-emulator-runner@31f424d05a8bea0229d0feab746260472ef8e34d`
   warned `Unexpected input 'enable-kvm'` (that pin's schema has no such
   input). KVM was already enabled by the separate udev-rules step.
2. The same action then ran `sudo mkdir /usr/local/lib/android/sdk/cmdline-tools`,
   which failed with `File exists` on the preinstalled GHA SDK, and cleanup
   reported `spawn adb ENOENT`.

The workflow now:

- keeps the Enable KVM udev step and **does not** pass `enable-kvm:`
- prepares `cmdline-tools/latest` (symlink to a versioned dir when needed)
  and puts `platform-tools` / `cmdline-tools/latest/bin` on `PATH`
- pins `reactivecircus/android-emulator-runner` at v2.38.0
  `a421e43855164a8197daf9d8d40fe71c6996bb0d` (full SHA)

`scripts/tests/p0-04-ci-workflow.sh` — `21 passed, 0 failed` after the
contract update.

To re-run device legs from this branch (or a PR targeting
`plan/P1-09-repository-crypto-hardening`):

```text
gh workflow run ci.yml --ref <branch> && gh run watch
```

`workflow_dispatch` and pull_request both start the instrumented matrix.
A hosted run that gets past emulator boot is still required before P0-04
can move to done; this change only removes the fail-in-20s setup bug.

PR run https://github.com/AieatAssam/openlife-android/actions/runs/35441212385
(from `cursor/p1-09-ci-intake-timeouts-f349`): both instrumented jobs
completed Enable KVM and **Prepare Android SDK cmdline-tools and adb**
successfully, then stayed in the emulator-runner step well past the
previous ~20s mkdir/`adb ENOENT` death. Full API 29/36 connected counts
are not claimed from this workspace.

## Hosted instrumented timeout (2026-09-19, run 35444099558)

PR run https://github.com/AieatAssam/openlife-android/actions/runs/35444099558
(`cursor/upload-debug-apk-artifact-c11f` against
`plan/P1-09-repository-crypto-hardening`): JVM and release jobs succeeded
in ~4 minutes. Both instrumented jobs reached `Starting emulator` and
`Emulator booted` (API 29 ~16s, API 36 ~54s), then failed immediately
with `/usr/bin/sh: 1: set: Illegal option -o pipefail`. The
`always()` `adb logcat -d` step then hung until job timeout (exit 143,
~35–40 minutes). This was not an app assertion failure and not a boot
hang.

The workflow now:

- pins instrumented jobs to `ubuntu-22.04` (Linux + KVM; not macOS, not
  floating `ubuntu-latest`)
- asserts `/dev/kvm` after the udev step
- runs connected tests via `bash scripts/ci/run-connected-android-tests.sh`
  because emulator-runner's `script` input is `/usr/bin/sh` (dash)
- bounds leftover `adb logcat -d` with `timeout 20s` and a 1-minute step
  timeout
- uses API 29 `google_apis` x86_64 (ATD does not exist for 29) and API 36
  `aosp_atd` x86_64; no `pixel_7` profile. See
  `docs/decisions/0016-ci-emulator-images.md`
- caches AVD snapshots and launches tests with `-no-snapshot-save`

`scripts/tests/p0-04-ci-workflow.sh` — `37 passed, 0 failed` after this
contract update.

## Hosted run past Gradle connected tests (2026-09-19, run 35447168861)

PR run https://github.com/AieatAssam/openlife-android/actions/runs/35447168861
(`cursor/ci-instrumented-emulator-eef4` against
`plan/P1-09-repository-crypto-hardening`):

| Job | Result | Wall time |
| --- | --- | --- |
| Build, lint, unit tests | success | ~3m 40s |
| Release APK boundary inspection | success | ~2m |
| Instrumented API 29 (`google_apis` x86_64) | failed (app assertions) | ~7m 41s |
| Instrumented API 36 (`aosp_atd` x86_64) | failed (app assertions) | ~6m 38s |

Both instrumented jobs created an AVD snapshot, booted, ran
`:vault:connectedDebugAndroidTest` and `:app:connectedDebugAndroidTest`,
captured logcat in 20s, and uploaded artefacts. The dash/`adb logcat`
hang from run 35444099558 did not recur. Assertion-shaped failures
were not retried (`connected test assertion failure; not retrying`).

Counts from the uploaded XML:

- API 29 vault: **72/72** pass. App: **34/38** pass.
- API 36 vault: **72/72** pass. App: **35/38** pass.

App failures (assertions, not infrastructure):

- both: `IntakeActivityTest#unavailableProviderIsRejectedGracefully`
- both: `AccessibilitySemanticsTest#screensMirrorCorrectlyUnderForcedRtl`
- both: `NavigationBackTest#backFromListFinishesActivity`
- API 29 only: `ViewerScreenC1Test#extractedTextIsInertAndCorrectionIsAttributable`

P0-04 stays `review`. These app failures are product/test issues for P1
steps, not a reason to revert the runner/image change. The debug-APK
artifact upload from PR #2 is not on this plan branch and is not part
of this change.
