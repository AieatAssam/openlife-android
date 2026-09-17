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
- REFACTOR extracts the three-job JDK/cache setup into
  `.github/actions/gradle-setup/action.yml`; its third-party actions remain
  pinned by full SHA and the contract test covers the composite action.
- `scripts/tests/p0-04-ci-workflow.sh` — `13 passed, 0 failed`.
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
