# P1-21 verification: minSdk 26 (Android 8.0)

Date: 2026-11-27. Branch `plan/minsdk-26`. Decision: ADR-0019.
No emulator and no adb were used (owner instruction). No instrumented test was
run on any device.

## Automated results (host, this commit)

Command: `./gradlew detekt lint :app:test :vault:test assembleDebug :vault:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin :eval:test`

- Result: `BUILD SUCCESSFUL in 4m 8s`, `136 actionable tasks: 47 executed, 5 from cache, 84 up-to-date`, `exit=0`.
- Lint: `app/build/reports/lint-results-debug.txt` reads `No issues found.` (the trial build had 5 errors).
- JUnit XML totals for app, vault and eval test tasks: `tests=506 skipped=0 failures=0 errors=0`.
- Targeted RED-to-GREEN: `AllowedAuthenticatorsTest` 2/2, `ManifestBoundaryTest` 7/7, `ApiLevelResourceTest` 1/1 (commit 0077c38).
- Merged release manifest (`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`): `<uses-permission android:name="android.permission.USE_FINGERPRINT" android:maxSdkVersion="27" />`; no `android.permission.INTERNET` line.

Command: `scripts/plan-check.sh` output: `steps=83 errors=0`.

Command: `bash scripts/tests/run.sh` output: `51 passed, 0 failed`.

Pre-existing configuration-cache warnings appeared (checkLicense tasks, DefaultProject); not introduced here.

## Device checklist (unverified: gap, pending physical-device run)

Device: owner's API 26 tablet. Install a debug build with `adb install` from the owner's machine. Record each item's result, date and build hash below. Until a row is filled in, the item is a gap.

| # | Check | Status |
| --- | --- | --- |
| 1 | App installs and launches on Android 8.0 (API 26) | unverified (gap — pending physical-device run) |
| 2 | App lock with fingerprint (BIOMETRIC_WEAK \| DEVICE_CREDENTIAL): prompt appears, fingerprint unlocks, cancel stays locked | unverified (gap — pending physical-device run) |
| 3 | App lock with PIN/pattern only (no fingerprint enrolled): device-credential prompt unlocks | unverified (gap — pending physical-device run) |
| 4 | Pending share survives a credential unlock (AppLockFlowTest A-12 behaviour on device) | unverified (gap — pending physical-device run) |
| 5 | Photo pick via the fallback picker (PickVisualMedia below API 33) opens and returns an image | unverified (gap — pending physical-device run) |
| 6 | Share intake from another app's share sheet, including a photo | unverified (gap — pending physical-device run) |
| 7 | OCR on a picked photo produces text (SampledBitmapDecoder and OcrBitmapPreparer paths) | unverified (gap — pending physical-device run) |
| 8 | About screen shows the signing-certificate fingerprint line (legacy `signatures` path) | unverified (gap — pending physical-device run) |
| 9 | About screen shows the wrapping-key line as "inside secure hardware" or software (no securityLevel on API 26-30) | unverified (gap — pending physical-device run) |
| 10 | Light navigation-bar icons correct in light theme (activity enableEdgeToEdge path; bytecode-checked only) | unverified (gap — pending physical-device run) |
| 11 | Dark theme and night navigation bar render without errors | unverified (gap — pending physical-device run) |
| 12 | Backup and restore on API 26 (backup_rules.xml; data_extraction_rules.xml is Android 12+ only) | unverified (gap — pending physical-device run) |

## Instrumented coverage gaps

- `SecureWindowUiTest.everyContentWindowAndDialogIsSecure` is skipped on API 26-28 (WindowInspector is API 29+). FLAG_SECURE on those levels has no automated check.
- `AppLockFlowTest` A-12 runs only on API 26-29 and skips elsewhere; it has not been run on any device.
- Nothing in CI runs API 26: the CI matrix has API 29 and API 36 legs only. Emulators are excluded by the owner.

## Source review of API-29-sensitive code (file:line)

- `app/src/main/java/org/openlife/app/settings/AppAboutPort.kt`: SDK_INT >= P branch uses `GET_SIGNING_CERTIFICATES` and `signingInfo`; below P it reads the legacy `signatures` with a targeted `@Suppress("DEPRECATION")`. Both branches yield the signing certificates.
- `app/src/main/java/org/openlife/app/lock/BiometricPromptAuthenticator.kt`: `allowedAuthenticatorsFor(sdkInt)`; STRONG from API 30, WEAK below. Covered by `AllowedAuthenticatorsTest`.
- `app/src/main/AndroidManifest.xml`: `USE_FINGERPRINT` with `maxSdkVersion="27"`.
- `app/src/main/res/values-v27/themes.xml` and `values-night-v27/themes.xml`: `windowLightNavigationBar`. Below 27 the activity's `enableEdgeToEdge()` sets the appearance.
- `vault/src/main/java/org/openlife/vault/crypto/KeystoreWrapper.kt`: options used are API 23+; StrongBox and unlocked-device options are not set; `securityLevel` read only at API 31+ (line ~77).
- `app/src/main/java/org/openlife/app/settings/VaultSecurityInfo.kt`: comments updated to API 26-30.
- `app/src/main/java/org/openlife/app/intake/IntakeActivity.kt` (around line 183): `TIRAMISU` guard on the parcelable extra helper. Fine at 26.
- `app/src/main/java/org/openlife/app/MainActivity.kt` (around line 90): `PickVisualMedia`. Below API 33 the AndroidX fallback is used. Not device-verified (checklist item 5).
- `MainActivity.kt` (around line 82) and `IntakeActivity.kt` (around line 87): `enableEdgeToEdge()`. Works on 26 through the activity library. Not device-verified (item 10).
- `app/src/androidTest/java/org/openlife/app/ui/SecureWindowUiTest.kt`: `WindowInspector` is API 29+; skipped below Q (see gaps).
- Not API-29-sensitive: `ImageDecoder` (not used); `BitmapFactory` in `SampledBitmapDecoder.kt` and `OcrBitmapPreparer.kt` (fine); `java.time` in vault facts and Type.kt fonts: not checked in this step; lint reported no API errors for them at minSdk 26.
