# P1-03 display orientation verification

Verification date: 2026-09-20. Device: local `dev36(AVD) - 16` (API 36).

## TDD and implementation

- RED commit `c3f529f`: the parser tests failed at compilation for the
  intended missing `ExifOrientationParser` symbol; instrumented tests used the
  required minimal decoder stub.
- GREEN commit `271cb13`: bounded JPEG APP1/Exif parsing, sampled bitmap
  transforms, shared orientation matrices, persistence, OCR coordinate
  mapping, and viewer details were implemented.
- The parser test covers all eight EXIF values in both TIFF byte orders,
  malformed/truncated data, PNG fallback, APP1-after-SOS rejection, and a
  10,000-mutation hostile-offset loop.

## Commands and results

| Command | Result |
| --- | --- |
| `./gradlew detekt --no-configuration-cache` | pass; `BUILD SUCCESSFUL`, 0 findings |
| `./gradlew :vault:test :app:test --no-configuration-cache` | pass; vault 71 tests and app 38 tests, 0 failures/errors/skips |
| `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.repository.ImportRepositoryTest --no-configuration-cache` | pass; 23/23 |
| `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.SampledBitmapDecoderTest --no-configuration-cache` | pass; 1/1 |
| `./gradlew :vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.vault.ocr.OrientedOcrRegionTest --no-configuration-cache` | pass; 1/1 |
| `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.openlife.app.ui.ViewerScreenC1Test --no-configuration-cache` | pass; 3/3 |
| `./gradlew :vault:connectedDebugAndroidTest --no-configuration-cache` | pass; 74/74 |
| `./gradlew :app:connectedDebugAndroidTest --no-configuration-cache` | gap; 41 tests, 39 passed, 2 existing `NavigationBackTest` failures |

The two app failures are `backFromListFinishesActivity` and
`backFromViewerReturnsToListInsteadOfFinishing`; the same failures were
reproduced against the pre-brand baseline commit `92cf9aa` and are documented
in `docs/verification/P0-09-visual-identity.md`. The new decoder, viewer
orientation-details, and intake/list paths passed.

The exact combined command from `plan/steps/P1-03.yaml` was also attempted:
the local emulator raced while installing the vault test APK and returned
`INSTALL_FAILED_PACKAGE_CHANGED`. The suites were then run sequentially; the
vault suite passed and the app result above is the independent full result.

No physical-device run was performed in this environment.
