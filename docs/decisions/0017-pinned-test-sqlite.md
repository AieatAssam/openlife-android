# ADR-0017: Pinned SQLite for the vault host tests

Status: accepted

Date: 2026-11-26

Decided by the coordinating session under the owner's 2026-11-24
delegation (acting owner; option 1 of the PR #36 CI options in
`docs/verification/P2-08-local-search.md`).

## Context

The vault JVM tests run the real migration, trigger and search SQL on a
host SQLite through `HostSqlite`. Until this decision that engine was the
host's python3 `sqlite3` module, so the result depended on the machine:

- ubuntu-22.04 ships SQLite 3.37.2. It has no FTS5 secure-delete, so no
  search index is created and the 13-14 index tests skipped on every CI run.
- ubuntu-24.04 ships SQLite 3.45.1. It has the FTS5 secure-delete
  corruption of 3.42.0-3.46.0 (ADR-0010, "Engine floor"), so three tests
  failed with "database disk image is malformed" (PR #36).
- The development host has 3.53.3.

None of these is the SQLite that ships: sqlcipher-android 4.19.0 bundles
SQLite **3.53.4** (static read of `libsqlcipher.so`, not observed on a
device).

## Decision

- The default `HostSqlite` engine is `org.xerial:sqlite-jdbc`
  **3.53.4.0**, used in-process. Its SQLite is 3.53.4, the same as
  SQLCipher 4.19.0's. This was checked by `sqlite_version()` on the jar and
  is enforced by `PinnedEngineTest`, which fails when the two drift apart
  (for example a SQLCipher bump without a matching sqlite-jdbc bump).
- **Licence (read from the artefacts).**
  - The POM declares "The Apache Software License, Version 2.0". That label
    is not a verbatim entry in `config/allowed-licenses.json`, which lists
    "Apache License, Version 2.0" and "Apache-2.0". The licence is Apache-2.0
    in meaning, so this record treats it as allowlisted by that judgement.
    The licence plugin's `checkLicense` does not check it, because it covers
    `releaseRuntimeClasspath` only (`app/build.gradle.kts`).
  - The jar also carries `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE`
    (Apache License 2.0 text) and `LICENSE.zentus`, a two-clause BSD
    licence (copyright 2006 David Crawshaw), which is BSD-2-Clause, also
    allowlisted.
  - The SQLite amalgamation inside is not covered by a licence file in the
    jar, and nothing here relies on its licence terms beyond that.
  - `checkLicense` and the committed licence report cover only
    `releaseRuntimeClasspath`, so this dependency is outside their scope by
    design. This record is its licence review.
- **Scope.**
  - The dependency is `testImplementation` of `vault` only, via the
    version catalog (`libs.sqlite.jdbc`).
  - It bundles host native libraries (`libsqlitejdbc.so`/`.dylib`/`.dll`)
    that must never reach an APK. `docs/dependency-policy.md` lists it under
    "Test-only dependencies".
  - `DependencyBoundaryTest` fails if it appears on the
    `releaseRuntimeClasspath`, `debugRuntimeClasspath` or
    `debugAndroidTestRuntimeClasspath` of `app` or `vault`. Vault's
    `debugUnitTestRuntimeClasspath` is the positive control. Recorded
    mutation: `androidTestImplementation(libs.sqlite.jdbc)` in `vault`
    failed the test with
    `vault debugAndroidTestRuntimeClasspath: org.xerial:sqlite-jdbc:3.53.4.0`.
  - It needs no other artefact: its only runtime dependency, `slf4j-api`,
    is optional in the POM and was not needed to load the driver.
- **Integrity.**
  - `gradle/verification-metadata.xml` gained only the sqlite-jdbc jar and
    POM entries, generated with
    `./gradlew --write-verification-metadata sha256 --dry-run help` (the
    ADR-0007/P0-02 procedure). The dry-run diff was reviewed, and only that
    component was added.
  - Trust path: TLS download from Maven Central (repo1.maven.org). The
    Gradle-computed SHA-256 equals both Central's published `.sha256` and an
    independent `curl` download: jar `bcb1f51e…cd2fb`, POM `ff588556…a7ab9`.
    The `.sha1` files also match. No PGP check: the repository runs with
    `verify-signatures=false`.
- **Overrides.**
  - `OPENLIFE_HOST_SQLITE` still selects another engine over the line
    protocol: `python3` (the host python, opt-in only) or a command such as
    `scripts/host-sqlite/LineHost.java` over another sqlite-jdbc jar.
  - Only then may a search-index test skip. On the pinned engine a missing
    index is a failure, so a default run skips none by construction.
- **CI** runs the default engine. The build job is back on ubuntu-22.04,
  and the python SQLite floor step is removed. `scripts/tests/p0-04-ci-workflow.sh`
  checks that the workflow never sets `OPENLIFE_HOST_SQLITE` and has no
  python SQLite floor step.

## What this does not prove

- sqlite-jdbc is not SQLCipher. It has no encryption codec, and its
  compile-time options differ from `libsqlcipher.so`.
- A pass here proves the SQL and trigger behaviour on SQLite 3.53.4, not on
  the device library.
- The device checks stay open (instrumented, device gap):
  - `sqlite_version()` on an opened vault is at least 3.46.1, or the index
    is absent;
  - FTS5 `'integrity-check'` passes after the delete and update sequences
    (`FtsAvailabilityTest`, `SearchRepositoryTest`).

## Alternatives rejected

- **Compile SQLite in the CI job.** It adds a toolchain step and a source
  download to every run, and local runs would still use a different engine.
- **Another runner image (ubuntu-24.04 or later).** The engine changes
  whenever the image does. 24.04's 3.45.1 is itself an affected engine, and
  it would never match the shipped SQLite.
- **Keep python and skip below the floor.** Skips hide the index tests on
  exactly the hosts where they matter.

## Consequences

- A SQLCipher upgrade must bump `sqliteJdbc` to the release that carries
  the same SQLite (`PinnedEngineTest` fails otherwise).
- Older-engine evidence (the 3.42.0-3.46.0 bisect) is a documented local
  procedure, not a CI leg, because those jars are not in the CI cache.
  `SearchIndexEngineGateTest` tests the version gate itself on any engine.
