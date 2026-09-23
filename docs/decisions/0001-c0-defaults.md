# Decision 0001 — Capability 0 engineering defaults

Date: 2026-09-13
Status: accepted for C0 implementation; app-lock and licence-for-distribution
items remain open per the design document.

## Context

`openlife-design-v0.2.md` §7 and §14 leave a set of engineering defaults for an
implementation agent to confirm against the actual build environment, with the
requirement to record the reason for any refinement and repeat affected tests
(§1, "Requirement language"). This records what was confirmed for the
environment this repository was built in, on 2026-09-13.

## Environment as found

| Item | Value |
| --- | --- |
| JDK | OpenJDK 21.0.12 |
| Gradle | 9.7.1 (wrapper; already present in the local wrapper cache) |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.10 |
| Jetpack Compose BOM | 2026.08.00 |
| Room | 2.8.5 |
| SQLCipher for Android | `net.zetetic:sqlcipher-android` 4.19.0 |
| Android SDK | read-only Nix-store path; platforms android-36 and
  android-37.0, build-tools 36.0.0 and 37.0.0, no NDK present |
| Emulator | AVD `dev36`: Pixel 7 profile, Android 16 (API 36), Google APIs,
  x86_64, KVM available |

## Decisions

1. **Modules**: two Gradle modules, `app` and `vault`, as specified. No
   dependency-injection framework; a small composition root in
   `OpenLifeApp.kt` wires dependencies explicitly.
2. **minSdk 29, compileSdk 37, targetSdk 37.** The design proposes minSdk 29;
   nothing in the environment forces a change, so it is retained. compileSdk
   and targetSdk are pinned to 37 — both the `android-37.0` platform and its
   build-tools are present, and several pinned AndroidX artifacts (Compose UI
   1.12.0, SQLCipher 4.19.0) require compiling against API 37 or later.
   The `dev36` emulator runs API 36, below targetSdk 37; this is a normal,
   supported configuration (a device's API level only needs to meet
   `minSdk`), not a mismatch requiring correction.
3. **Room + `net.zetetic:sqlcipher-android` 4.19.0**, integrated via Room's
   classic (2.x) `SupportSQLiteOpenHelper.Factory` API — concretely,
   `net.zetetic.database.sqlcipher.SupportOpenHelperFactory(password)` passed
   to `Room.databaseBuilder(...).openHelperFactory(...)` — confirmed by
   decompiling the pinned AAR's `classes.jar` rather than assumed, since Room
   3's newer driver-based API (a different, incompatible integration
   surface) is documented to also exist for other databases and the two must
   not be mixed. Not the deprecated `android-database-sqlcipher` package.
   **Correction from initial static analysis:** there is no public
   `loadLibs` entry point in this artifact (confirmed from the decompiled
   classes), which was initially taken to mean the native library loads
   itself automatically. It does not — an instrumented-test
   `UnsatisfiedLinkError` on `SQLiteConnection.nativeOpen` on 2026-09-13
   proved the AAR's bundled `jni/<abi>/libsqlcipher.so` is never loaded on
   its own. `OpenLifeDatabaseFactory.create` now calls
   `System.loadLibrary("sqlcipher")` before every open (a documented
   no-op on repeat calls). Lesson recorded because it is exactly the kind
   of thing static decompilation cannot substitute for actually running
   the code (design §13: "A mocked encryption layer cannot prove Keystore
   behaviour" — the same is true of native library loading). See
   `vault/src/main/java/org/openlife/vault/storage/OpenLifeDatabaseFactory.kt`.
4. **Testing-API gap (owner-relevant, recorded here per the design's
   traceability requirement).** The only installable Android system image in
   this environment is API 36. No API 29 image can be added because
   `$ANDROID_HOME` is a read-only Nix store path — `sdkmanager` cannot write
   into it, and no writable SDK overlay was created for this build. P0-04 now
   defines the required API-29 and API-36 GitHub Actions matrix; until an
   authenticated CI run produces a URL and artefacts, the matrix is configured
   but its API-29 evidence is not claimed.
   Consequently:
   - The app is still **built** with `minSdk 29`, preserving support for
     Android 10+ devices.
   - All instrumented/adversarial tests run locally in this repository's
     evidence against API 36 only; the API-29 leg is delegated to P0-04 CI.
   - `docs/verification/C0.md` and the C0 handoff record the lowest-supported
     API leg of test C0-16 as **not run locally**, rather than reporting it as
     passed. Closing the evidence gap requires a successful P0-04 CI run (or a
     writable SDK with an API 29/30 system image, or a physical low-API
     device).
5. **Licence: Apache-2.0**, added at the repository root as `LICENSE`. This
   satisfies P6's "choose a project licence before public distribution" for
   the current development phase; it does not itself constitute public
   distribution.
6. **Bounded whole-file authenticated reads** and **device-lock-only access**
   (no separate biometric/passcode lock in C0) are retained as proposed. The
   design states the app-lock question must be resolved before distributing
   C0 to real users — this remains open and is not resolved by this decision
   record.

7. **`android.builtInKotlin=false`.** AGP 9.0 made Kotlin compilation
   built-in and deprecated the `org.jetbrains.kotlin.android` plugin in
   favour of a new `android.kotlin { }` DSL block. This project opts out via
   the documented `android.builtInKotlin=false` Gradle property
   (`gradle.properties`) and keeps applying `org.jetbrains.kotlin.android`
   explicitly, because that plugin surface is the one Room, SQLCipher, and
   Compose documentation assumes, and is far better exercised than a DSL that
   shipped with this AGP line. Android documents this opt-out as removed in
   AGP 10 (mid-2026); moving to the built-in-Kotlin DSL is a future decision,
   not an accidental side effect of a later AGP bump.

8. **READY-invariant enforcement via SQL triggers, not a declarative CHECK
   constraint.** Design §9 calls for "a database constraint" requiring every
   validated Source field to be non-null once `state = READY`. Room 2.8.5
   has no `@Entity(checkConstraints = ...)` or equivalent annotation
   (confirmed by inspecting the `room-common`/`room-compiler` jars for any
   `CheckConstraint`-shaped class — none exists in this release), and SQLite
   itself does not support adding a `CHECK` constraint to a table after
   creation via `ALTER TABLE`. Instead, `OpenLifeDatabase.readyInvariantCallback`
   creates `BEFORE INSERT`/`BEFORE UPDATE` triggers on the `sources` table
   (in `RoomDatabase.Callback.onCreate`) that `RAISE(ABORT, ...)` when a row
   with `state = 'READY'` has any validated field still null. This is
   genuine SQL-level enforcement, independent of and in addition to the
   repository-level enforcement design §9 also requires ("The repository
   enforces legal state transitions") — it is not a weakening of the
   requirement, just a different mechanism than a literal `CHECK` clause.

## Consequences

- C0's acceptance evidence will explicitly show one incomplete test matrix
  leg (lowest-API device testing) rather than a false pass. Per §13's release
  acceptance rule, if a required device test cannot run, the capability is
  reported as incomplete for that leg — this decision exists so that gap is
  visible and attributable, not silently absorbed.
- Anyone continuing this work in an environment with a writable SDK should
  add the API 29/30 system image, re-run the instrumented suite there, and
  update this record and `docs/verification/C0.md` accordingly.
