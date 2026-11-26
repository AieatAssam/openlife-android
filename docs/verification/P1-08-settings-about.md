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
   This catches any `startActivity` from the app process, just as
   `intended(none)` would.
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
- JVM counts: app 204 (main: 189), vault 193, eval 26. No failures and no
  skips.
- `scripts/plan-check.sh`: steps=82 errors=0. `scripts/tests/run.sh`: 51
  passed, 0 failed.

`R.raw.ofl_1_1` no longer has a code reader. It is kept because it ships
the font licence verbatim next to the fonts, and `generateLicenseReport`
appends it to the licence report that About shows.

## Awaiting a physical device (prioritised checklist)

Run on the tablet (`U8BBB20806201256`) once no other agent is using it:

1. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.settings.AboutScreenTest`
   — version, commit, fingerprint, security label, licences and policy
   render, and no activity starts.
2. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.settings.SettingsScreenTest`
   — reset requires the exact word.
3. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.CoreFlowUxTest`
   and `...class=org.openlife.app.ui.AdaptiveLayoutTest`. These now use
   `org.openlife.app.settings.AboutScreen`.
4. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.LargeFontUiTest`
   and `...class=org.openlife.app.ui.RtlUiTest`. Both reach About through
   `FakeAppContainer`, now with a `FakeAboutPort`.
5. Manual check:
   - Install the debug APK and open About.
   - Record `adb shell uiautomator dump` showing the key-level label
     (expected on the tablet: trusted execution environment or StrongBox).
   - Compare the fingerprint with `apksigner verify --print-certs` for the
     same APK.
   - Open the licences and scroll to the end to check for jank or ANR.
6. Golden: decide whether About joins `BrandScreenshotInstrumentedTest`.
   It was not added here.
