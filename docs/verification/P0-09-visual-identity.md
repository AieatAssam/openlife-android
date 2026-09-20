# P0-09 visual identity verification

Date: 2026-09-20  
Branch: `plan/P0-09-visual-identity`

## TDD evidence

- RED `dbee5ef`: the four named brand tests failed for their intended missing
  asset/component/token/golden/reduced-motion contracts.
- GREEN `799c439`: implemented the visual identity assets, tokens, fonts,
  reusable brand components, screen integration, screenshot goldens, and
  licensing documentation.
- REFACTOR `368b052`: extracted `StampState` and motion constants, reduced the
  font payloads to the documented Latin ranges, and completed Detekt cleanup.

## Verification commands

| Command | Result |
| --- | --- |
| `./gradlew :app:test --no-configuration-cache` | `BUILD SUCCESSFUL` |
| `./gradlew :app:detekt --no-configuration-cache` | `BUILD SUCCESSFUL`, 0 findings |
| `./gradlew :app:connectedDebugAndroidTest --no-configuration-cache` | 39 tests: 37 passed, 2 failed in the existing `NavigationBackTest` |
| `ls -l app/build/outputs/apk/debug/*.apk` | `app-debug.apk`, 66,908,070 bytes |

The new `BrandScreenshotInstrumentedTest` passed 1/1 on the dev36 API 36
emulator. Eight light/dark synthetic golden PNGs are committed. The current
subsetted font files total 592,904 bytes on disk and occupy 334,027 compressed
bytes as APK entries.

The two connected failures are a baseline gap, not a P0-09 regression. Running
the exact `NavigationBackTest` class against pre-brand commit `92cf9aa`
reproduced both failures: the viewer test timed out waiting for the saved-image
node, and the list-back test raised `RootViewWithoutFocusException`. No device
data was reset or deleted during this comparison.

## Design review

The installed Material 3 Expressive, mobile Android design, and colour-system
skills were reviewed. The implementation keeps dynamic colour disabled,
uses fixed paper/ink/vermilion tokens, provides non-colour state labels, and
uses the documented 160 ms stamp motion with a zero-scale snap fallback.
Contrast, stamp-state, screenshot, and reduced-motion contract tests are in
`app/src/test/java/org/openlife/app/ui/brand/`.
