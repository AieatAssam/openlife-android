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
