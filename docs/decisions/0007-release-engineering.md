# ADR-0007: Release dependency verification and audit artefacts

Status: accepted for the current P0 release baseline

## Context

OpenLife ships an offline Android application. A pinned version is not a
proof that the resolved bytes are the intended bytes, and a dependency tree
does not make transitive telemetry or licence drift visible. Release builds
therefore need Gradle verification metadata, a CycloneDX SBOM, and a committed
runtime licence inventory.

## Decision

- Gradle dependency verification is enforced with SHA-256 metadata. An
  unverified artefact fails the build; no warning-only mode is used.
- `org.cyclonedx.bom` 3.4.1 generates the aggregate SBOM at
  `app/build/reports/bom/bom.json` with serial numbers disabled for stable
  diffs. The release workflow attaches the file in P0-05.
- `com.github.jk1.dependency-license-report` 3.1.4 examines only
  `releaseRuntimeClasspath`, writes `docs/generated/THIRD_PARTY_LICENSES.md`,
  and fails `checkLicense` when a dependency has no allowed licence.
- The source allowlist is `config/allowed-licenses.json`; unknown licences
  require a new decision record rather than a silent override. The SQLCipher
  Community Edition override is supported by its BSD-style source licence and
  is pinned to the exact coordinate in `config/license-overrides.txt`.
- `DependencyBoundaryTest` reads the generated release classpath and the
  committed licence report. It runs from `:app:test` and fails on the
  denylist in `docs/dependency-policy.md`.

## Time-boxed licence and dependency exceptions

The current baseline has two documented non-standard licence labels:

1. Android Software Development Kit License: platform support artefacts only.
2. ML Kit Terms of Service: the existing bundled ML Kit OCR dependency while
   the open-source replacement is evaluated.

Both are recorded in the machine allowlist and must be removed or separately
re-approved when the dependency is removed or upgraded. The ML Kit
`com.google.android.datatransport`/Firebase transitive support classes are one
boundary exception record, `mlkit-transitive-transport`, and expire at P2-05.

## Consequences

Builds need the pinned plugin and dependency artefacts in the local Gradle
cache; the verification command is offline. The committed SBOM and licence
report are reviewable diffs, while the release workflow remains responsible
for attaching the SBOM to the GitHub Release.

## Verification

The implementation and verification commands are defined in
`plan/steps/P0-02.yaml`. The checksum corruption check is a documented CI
manual check because it must mutate a disposable verification fixture rather
than the committed metadata.

## Static analysis and boundary enforcement

P0-03 makes Detekt the repository's single Kotlin static-analysis entry point.
The committed `config/detekt/detekt.yml` uses official Kotlin formatting with a
120-column limit, permits the Compose convention of capitalized composable
names, and keeps the complexity limits at LongMethod 80 lines and
CyclomaticComplexMethod 15. The Detekt 2.x formatting ruleset is provided by
`dev.detekt:detekt-rules-ktlint-wrapper`; no separate ktlint plugin is used.
There is no Detekt baseline, and the root `detekt` task runs both shipped
modules without type resolution.

Android Lint is configured in both modules with `abortOnError`,
`warningsAsErrors`, and `checkReleaseBuilds` enabled. Security, Correctness,
and Performance findings therefore fail the build; Accessibility remains an
explicit warning until P1-10. `app/lint.xml` contains only the documented
version-advisory suppressions for the pinned dependency policy and the
profileinstaller merge-marker `MissingClass`; `vault/lint.xml` contains no
suppression. The release-manifest boundary test depends on complete release
manifest processing and rejects broad permissions or exported components
outside the two intentional activities. The source boundary test rejects
logging, clipboard/WebView channels, and outbound intent actions outside the
named intake/same-app paths.
The app module's `lint` task also depends on its JVM boundary suite, ensuring
the standard `lint` command exercises the same manifest and source-egress
assertions as `:app:test`.

## CI matrix (P0-04)

The CI workflow uses Temurin JDK 21 in every job, runs the plan/static-analysis
and dependency-verification build command, and executes connected tests on
API 29 (`google_apis` x86_64) and API 36 (`aosp_atd` x86_64) emulators with
KVM enabled on `ubuntu-22.04`. CI does not use a `pixel_7` hardware profile;
see `docs/decisions/0016-ci-emulator-images.md`. Third-party actions are
pinned to full commit SHAs. Instrumented reports,
filtered OpenLife logcat, lint/Detekt reports, and release-inspection output
are retained as 30-day artefacts. The connected runner retries once only for
infrastructure-shaped failures; assertion-shaped failures stop immediately.
An authenticated workflow URL is not recorded until the owner runs the
workflow from a pushed ref.

## Release signing and provenance (P0-05)

Release signing is an explicit CI boundary. `app/build.gradle.kts` reads the
four `OPENLIFE_*` signing variables only from the environment, decodes the
owner-generated keystore into a temporary build-directory file, and removes
it when Gradle finishes. With no signing variables, `assembleRelease` still
works but produces an unsigned release APK/AAB; it never falls back to the
debug key. Partial secret sets fail closed.

The release workflow validates a tag against `VERSION` and `CHANGELOG.md`,
builds the APK and AAB, verifies the APK with `apksigner`, and attaches the
SBOM, runtime licence report, `SHA-256SUMS`, and certificate fingerprint only
for a signed release. Version codes use
`major * 10000 + minor * 100 + patch`. v1 through v4 APK signing are enabled;
R8 full mode and Play App Signing remain P0-06/owner decisions.
