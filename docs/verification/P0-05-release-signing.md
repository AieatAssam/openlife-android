# P0-05 — Release signing, versioning, and store metadata

Status: review (2026-09-20)

## TDD evidence

- RED `3419c36`: `VersionCodeTest.versionCodeDerivesFromVersionFile` failed
  with the intended missing `VERSION` file; the release-workflow contract
  failed because the old workflow had no actionlint path.
- GREEN `1bb9ab6`: added `VERSION`/`gradle/version.gradle.kts`, secret-only
  release signing, v1–v4 signing configuration, the tag/changelog gate,
  release evidence packaging, signing runbook, changelog, and fastlane
  metadata.
- REFACTOR `42354aa`: centralized version and signing inputs and kept the
  release workflow on the P0-04 Gradle composite setup.
- Verification fix `e1fc574`: corrected the new test's import ordering;
  Detekt then passed.

## Verification commands

- `./gradlew :app:test printVersionCode` — `BUILD SUCCESSFUL`; the task
  printed `100` for `VERSION=0.1.0`.
- `OPENLIFE_KEYSTORE_B64= ./gradlew assembleRelease` — `BUILD SUCCESSFUL`,
  96 actionable tasks; the clean release output was
  `app-release-unsigned.apk`. The required APK listing command returned 86
  `META-INF/` entries; the stronger signature-entry check found 0 `.RSA`,
  `.SF`, or `.DSA` entries.
- Synthetic disposable signing-key check — release APK verification passed
  with the temporary key; the decoded keystore was removed after Gradle
  finished. The generated signing-config metadata reported all four v1–v4
  flags enabled for the signed variant. The production fingerprint was not
  invented or recorded from this disposable key.
- `./gradlew detekt lint :app:test :vault:test assembleDebug` —
  `BUILD SUCCESSFUL`, 114 actionable tasks; Detekt and lint completed without
  findings.
- `bash scripts/tests/p0-04-ci-workflow.sh` — 38 passed, 0 failed.
- `bash scripts/tests/run.sh` — 46 passed, 0 failed.
- P0-05 actionlint contract — actionlint 1.7.7 exited 0 against
  `.github/workflows/release.yml`; the workflow also runs pinned
  `raven-actions/actionlint` in CI.
- `scripts/plan-check.sh` — `steps=77 errors=0`; runnable steps are
  `[P0-09, P1-03, P1-04, P1-05, P1-06, P1-13, P1-15, P2-02]`.
- `git diff --check` — clean after generated licence artefacts were removed.
- Hosted run [35477169375](https://github.com/AieatAssam/openlife-android/actions/runs/35477169375)
  on commit `97a0bf1` — workflow-lint passed; the release build completed
  successfully in 4m21s, then correctly took the `unsigned (no secrets)` path.
  No release evidence artifact or GitHub Release was created.
- Hosted signed dry run [35478365545](https://github.com/AieatAssam/openlife-android/actions/runs/35478365545)
  on commit `9ea5e6b` — workflow lint and the keystore-backed release build
  passed. The signing verification, signed-evidence packaging, and upload
  steps passed; the unsigned path and GitHub Release were skipped because
  `dry_run=true`. The downloaded `openlife-release-evidence` artifact contained
  the signed APK/AAB, SBOM, licence report, checksum manifest, and certificate
  fingerprint file. Local checks passed for APK signature verification,
  fingerprint-file presence, and every recorded checksum.

## Review gaps and owner action

The four repository secrets are configured and the signed-CI acceptance
criterion is verified by hosted run `35478365545`. Before the first public tag,
the owner must complete OA-1 by publishing the production certificate
fingerprint in `README.md` and comparing it with the workflow evidence. The
step remains handed off as `review`, not claimed `done`.
