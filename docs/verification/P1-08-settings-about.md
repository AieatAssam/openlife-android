# P1-08 Settings and About screens

Branch `plan/P1-08-settings-about`, from `main` at bc3f0fc. Date 2026-11-26.
Addresses F-20; closing it depends on the device run below.

P1-06, P1-07 and P1-20 are at `review`, not `done`. The acting owner (the
coordinating session, under the 2026-11-24 delegation) authorised starting
this step on top of them.

## Device policy for this run

Owner instruction: no emulator, no `connected*AndroidTest` task and no `adb`.
None was started or run. Instrumented tests were **compiled**
(`./gradlew :app:compileDebugAndroidTestKotlin`) and **not run**. Each one is
**implemented; unverified (gap — pending physical-device run)**.

## What was built

- **R1, routes.** `Routes.Settings` and `Routes.About` already existed (P1-17,
  P1-20) and are reached from the list overflow menu. About is now an
  entry-scoped destination: `AboutViewModel.factory(container)` with
  `viewModelStoreOwner = entry`, fed by the new `AppContainer.about`
  (`AboutPort`). Settings moved to `settings/SettingsScreen.kt`. It now has
  a heading for the app-lock section and a "Vault" heading over
  verify-all and reset, separated by the perforation divider.
- **R2, build identity.** `BuildConfig.GIT_COMMIT` is set from
  `git describe --tags --always --dirty` through `providers.exec`. If git
  fails or the output has unexpected characters, the value is `unknown`.
  The signing fingerprint is the SHA-256 of each `signingInfo.apkContentsSigners`
  certificate, read at runtime from PackageManager and formatted as
  colon-separated upper-case hex, the same format as the README.
  The licence report and privacy policy are copied byte for byte by the
  per-variant task `bundle<Variant>OfflineDocuments` into a generated res
  directory. It is registered with `variant.sources.res.addGeneratedSourceDirectory`,
  so there is no committed duplicate. The screen reads them on IO only
  when the user opens them, parses the markdown into plain lines on
  `Dispatchers.Default`, and shows the lines as items of the one `LazyColumn`.
- **R3, vault security details.** `KeystoreWrapper.wrappingKeySecurity()`
  reads `KeyInfo` through `SecretKeyFactory` (AES keys) for the *existing*
  wrapping key and never creates one. It returns `securityLevel` on API 31+
  and `isInsideSecureHardware` on all levels. `VaultSecurityInfo` maps this
  to StrongBox / trusted execution environment / secure hardware (type not
  reported) / software / not reported / no key yet. Only the first three
  labels mention secure hardware, and no string says "hardware-backed"
  (a guard test checks this). The section also shows the database,
  file and backup facts, the boundary sentence, and the five "will not do"
  items with the rationale.
- **R4, diagnostics.** `DiagnosticsCounts.rows()` returns counts keyed by
  enums only:
  - sources by state, from `SourceViewRepository.countSourcesByState()`,
    read only when the vault is already open;
  - the eight `RecoveryReport` counters;
  - vault-open failure causes, from `VaultFailureDiagnostics.counts`.

  It holds no identifier or hash. The signing fingerprint is kept out of
  the diagnostics.
- **R5, no links.** No string or component opens a URI. The licence and
  policy text shows addresses as text, followed by the note "Web addresses
  are shown as text only. OpenLife does not open links."
  `SourceEgressBoundaryTest.noLinkOpeningApiIsReported` now rejects these
  in shipped code: `LocalUriHandler`, `UriHandler`, `openUri`,
  `LinkAnnotation`, `UrlAnnotation`, `fromHtml`, `Linkify`, `CustomTabs*`,
  `launchUrl` and `autoLink*`.

## Deviations from the step text

1. **Intent check.** The step asks for `Intents.init()` + `intended(none)`.
   `espresso-intents` is not in the version catalog or in
   `gradle/verification-metadata.xml`. `AboutScreenTest` instead registers
   a catch-all blocking `Instrumentation.ActivityMonitor(null as IntentFilter?, null, true)`
   and asserts zero hits after clicking every button and a URL line.
   It is designed to catch any `startActivity` from the app process.
2. **Raw resource location.** The step names
   `app/src/main/res/raw/third_party_licenses.md`. AGP 9 refuses provider
   directories in the source-set API, and a committed copy could drift. The
   copy is therefore generated under `app/build/generated/res/bundle<Variant>OfflineDocuments/raw/`.
   `BundledDocumentsTest` checks that each generated copy is byte-equal to
   the docs and that no committed copy exists. The copy task does not
   depend on `generateLicenseReport` (that task needs the network-resolved
   classpath). It only `mustRunAfter` it.
3. **KeyFactory vs SecretKeyFactory.** The wrapping key is an AES
   `SecretKey`, so `KeyInfo` comes from `SecretKeyFactory` (`KeyFactory` is
   for asymmetric keys).
4. **Settings test.** `SettingsScreenTest.resetVaultRequiresTypedConfirmation`
   is a new instrumented test on `ResetVaultFlow`. The JVM gating in
   `SettingsController` (P1-06) is unchanged, so "Continue" stays gated in
   the UI on the exact word.
5. **Evidence.** The step asks for a uiautomator dump of About showing the
   security level. That is not possible without a device: **gap**.
   No About golden was generated either.

## Commands run and results

- RED (6c1b2fc): 20 targeted JVM tests, 10 failed as intended; the
  androidTest sources compiled against `TODO()` stubs.
- GREEN (a58eff4): detekt clean; `:app:testDebugUnitTest` 200 tests, 0
  failures.
- REFACTOR (459ceeb): `AboutControllerTest` (4 tests).
- Final gate. Two whole-gate runs were killed by another session's
  `gradlew --stop` ("daemon disappeared", "stop command received"), so the
  same tasks ran in four chunks with `--max-workers=2`, all
  `BUILD SUCCESSFUL`:
  - `detekt :vault:test :eval:test`
  - `:app:test`
  - `lint` (after `res/raw/keep.xml` keeps `R.raw.ofl_1_1`)
  - `assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin`
- After that, `res/raw/keep.xml` was added and every `Test` task was made
  to depend on the `bundle*OfflineDocuments` tasks. Then
  `./gradlew --no-daemon --max-workers=2 detekt lint` ran; `lint` depends
  on `test`. Result: `BUILD SUCCESSFUL`.
- JVM counts: app 204 (main: 189), vault 193, eval 26. No failures and no
  skips.
- `scripts/plan-check.sh`: steps=82 errors=0. `scripts/tests/run.sh`: 51
  passed, 0 failed.

`R.raw.ofl_1_1` no longer has a code reader. It is kept because it ships
the font licence verbatim next to the fonts, and `generateLicenseReport`
appends it to the licence report that About shows.

## Review round (K-01..K-08, acting-owner decisions)

An independent review returned "accept after fixes". What changed:

- **K-01.** The no-key label now reads "No vault key is present in Android
  Keystore." It no longer promises that a key will be created: if the key
  file outlives its Keystore key, bootstrap returns `KEY_UNWRAP_FAILED` and
  creates none. The other About and Settings strings were checked and make
  no promise about future behaviour. The wording rests on the review alone;
  no device test covers it.
- **K-02.** `AboutController` loads each fact separately. If a read throws
  anything other than cancellation, that fact shows "Could not be read."
  (build identity, diagnostics) or "not reported" (key level). The other
  facts still load. Nothing is logged. `AboutControllerTest` uses throwing
  fakes.
- **K-03.** `SourceDao.countByState()` runs
  `SELECT state, COUNT(*) AS n FROM sources GROUP BY state`. It is placed as
  the last member of the interface, after the lines PR #32 edits. The result
  is decoded with the tolerant state decoding: any unknown text counts
  towards UNRECOGNISED. `SourceStateCountsTest` runs the query on host
  SQLite against the exported v5 schema.
- **K-04.** `AboutScreenTest` builds `AppAboutPort(application)` directly,
  so it never reads `application.container`, opens no vault and creates no
  key. It computes the expected key label itself from `KeyInfo`.
- **K-05.**
  - The version code is matched together with its own label; `Fact` nodes
    now merge their label and value.
  - `BundledDocumentsTest` requires `GIT_COMMIT` to differ from "unknown"
    whenever `.git` exists.
  - The address check asserts that the address node has no click action,
    then taps it and asserts that no activity started. This proves only
    that this text is not a link.
- **K-06.**
  - `DependencyBoundaryTest.licenceReportListsExactlyTheReleaseRuntimeClasspath`
    compares the release classpath coordinates with the report rows.
    Project modules (version "unspecified") are excluded. Two recorded
    mutations are part of the test: a shipped dependency with no report
    row, and a report row for a dependency no longer shipped.
  - The copy task still does not `dependsOn` `generateLicenseReport`. That
    task rewrites the committed `docs/generated/THIRD_PARTY_LICENSES.md`,
    so every build would dirty the tree and stamp `GIT_COMMIT` with
    `-dirty`. The boundary test is the enforcement instead.
- **K-07.** The expected device label is corrected (below). StrongBox is
  impossible because `ensureWrappingKey` never requests it.
- **K-08.** `AppAboutPort.diagnostics` awaits
  `appLock.awaitContentAccess()` first.
- **Boundary.**
  - `AndroidUriHandler` and any other `*UriHandler` now match.
  - `noResourceOrManifestEnablesLinks` scans the XML of the app's main,
    debug and release source sets for `autoLink`, BROWSABLE and http(s)
    `android:scheme`. It has recorded mutations.
- **Reset guard (requirement added by review; acting owner, 2026-11-26).**
  - `VaultResetRepository.resetVault`, `OpenLifeApp.resetVault` and
    `SettingsPort.resetVault` all require a `ResetConfirmation`. Its
    constructor is private.
  - `ofTypedWord` mints one only for the exact, non-blank word. It is
    called only by `SettingsController.continueReset`.
  - `ofIncompleteReset` mints one only while the reset marker exists. It
    is called only by `OpenLifeApp.finishIncompleteReset`.
  - `ResetConfirmationBoundaryTest` enforces both rules with mutations.
  - A completed reset spends the confirmation; dismissing the flow clears
    it. The confirmation UX is unchanged.
- **Owner decisions.** Keep the blocking ActivityMonitor (no
  espresso-intents) and the generated document copy. No About golden in
  this step; it is listed below for the tablet golden regeneration.

## Awaiting a physical device (prioritised checklist)

Run on the tablet (`U8BBB20806201256`) once no other agent is using it:

0. **First**, confirm that the tablet holds no personal `org.openlife`
   install or data (`adb shell pm list packages org.openlife`). The debug
   build has no `applicationIdSuffix`, so installing it replaces any
   OpenLife on the device, and the tests would act on that vault.
1. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.settings.AboutScreenTest`
   checks:
   - version, commit and fingerprint are shown;
   - the key label matches what the test computes from KeyInfo;
   - the licences and the policy render;
   - no activity starts.
2. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.settings.SettingsScreenTest`
   checks that reset requires the exact word.
3. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.CoreFlowUxTest`
   and `...class=org.openlife.app.ui.AdaptiveLayoutTest`. Both now use
   `org.openlife.app.settings.AboutScreen`. Also run
   `...class=org.openlife.app.ui.VaultUnavailableFlowTest`, since the reset
   now needs the confirmation.
4. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.LargeFontUiTest`
   and `...class=org.openlife.app.ui.RtlUiTest`. Both reach About through
   `FakeAppContainer`, now with a `FakeAboutPort`.
5. `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.VaultResetRepositoryTest`
   (it now passes a minted confirmation).
6. Manual check:
   - Install the debug APK and open About.
   - Record `adb shell uiautomator dump` showing the key-level label.
     Expected: "trusted execution environment" on API 31+. On API 29-30,
     expect "secure hardware, type not reported". StrongBox is never
     requested, so it cannot appear.
   - Compare the fingerprint with `apksigner verify --print-certs` for the
     same APK.
   - Open the licences and scroll to the end to check for jank or ANR.
7. Golden: no committed golden captures About or Settings, so none went
   stale. Per the owner, About gets no golden in this step. Add it to the
   tablet golden regeneration with `BrandScreenshotInstrumentedTest`.
8. The K-01 label wording rests on review only. No test exercises a key
   file whose Keystore key is gone.

## Fix-round verification (2026-11-26)

An independent reviewer read the K-01..K-08 fixes and the reset guard and
accepted the step for merge. Nothing in this section has run on a device.

Open, low severity, recorded from that pass:

- **KK-01.** The boundary check that confines who may mint a
  `ResetConfirmation` matches the literal text `ofTypedWord(` and
  `ofIncompleteReset(`; a function reference or a space before the bracket
  would pass it. Match the names on word boundaries instead.
- **KK-02.** The XML link check scans `app/src/{main,debug,release}` only, not
  `vault/` resources (none carry links today).

Added to the on-device checks:

- With the app lock engaged, About's diagnostics stay on "Loading…" until
  unlock and nothing hangs.
- `VaultUnavailableFlowTest`: the typed-word reset and the "finish reset" path
  still complete.
- TalkBack reads each merged fact as label, then value.
