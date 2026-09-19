# P0-07 verification: UI foundation

Date: 2026-09-17  
Branch: `plan/P0-07-ui-foundation`  
Status: implementation complete; ready for owner review

## TDD record

- RED `c65ab24`: the seven new contract tests failed for the intended missing
  navigation, inset, secure-window, RTL, large-font, and hardcoded-string
  contracts; 32 JVM tests were discovered.
- GREEN `eb12680`: added the theme, resources, navigation host, adaptive icon,
  splash theme, secure-window policy, and activity/screen wiring.
- REFACTOR `899ec4e`: corrected Detekt/Kotlin formatting and split the viewer
  into focused top bar, content, and correction-dialog composables without
  changing the UI contract.
- Review closure `468dae4`: added committed instrumented back-navigation and
  secure-window tests, guarded cold-start deep links, and completed the
  large-font, RTL, inset, typography, contrast, and scoped-lint review fixes.
- Icon geometry follow-up `d04ac8c`: applied the required 3dp rounded paper
  corners to the adaptive foreground and monochrome vectors.

## Local verification

| Command | Result |
| --- | --- |
| `./gradlew :app:detekt` | pass; `BUILD SUCCESSFUL`, 0 findings |
| `./gradlew :app:testDebugUnitTest --rerun-tasks` | pass; `BUILD SUCCESSFUL`; 32 tests, 0 failures, 0 errors |
| `./gradlew :app:compileDebugAndroidTestKotlin` | pass; `BUILD SUCCESSFUL`; the 400x700dp/48dp inset assertion and committed `NavigationBackTest`/`SecureWindowTest` compile |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | pass after REFACTOR/final lint fixes; `BUILD SUCCESSFUL`, 114 actionable tasks; app/vault JVM tasks, Detekt, lint, and debug assembly passed |
| `git diff --check` | pass after generated-report cleanup |

The unit contracts cover typed Navigation Compose routes, back-stack shape,
deep-link loading guards, edge-to-edge/inset declarations, secure windows, and
resource-only UI strings. `AccessibilitySemanticsTest` contains Compose checks
for 1.3x/2x font scale on first-run, intake, list, and viewer actions, a
400x700dp window with a simulated 48dp bottom inset, privacy descriptions, and
forced RTL geometry. Instrumented `NavigationBackTest` and `SecureWindowTest`
now exercise Espresso/activity behavior when a device is available.

## Device and hosted gaps

The final-tree connected command was run on 2026-09-17:

```text
./gradlew :app:connectedDebugAndroidTest
BUILD FAILED — :app:connectedDebugAndroidTest
com.android.builder.testing.api.DeviceException: No connected devices!
```

A fresh retry started the local `dev36` API-36 AVD, but it remained online
without `sys.boot_completed=1`; the package service did not become available
for APK installation. Therefore no P0-07 connected test count or `uiautomator`
bounds result is claimed here.

The required font-scale commands were also attempted and both returned
`adb: no devices/emulators found` (set/reset return code 1); the
`uiautomator dump`/bounds inspection could not run because there was no usable
device. TalkBack and the hosted API-29/API-36 CI legs are also not locally
executable; P0-04 records the owner-controlled hosted CI requirement. The
previous API-36 C0 large-text evidence remains in
`docs/verification/C0.md`, but is not presented as a final-tree P0-07 connected
run.

## Scope and safety checks

No vault behaviour, permissions, network capability, provider boundary, or
outbound action was added. Navigation arguments carry UUID strings only. The
generated dependency licence report and SHA-256 verification metadata include
the new AndroidX/navigation/serialization graph; no untracked disposable
licence-report JSON is retained.

## Hosted CI assertion follow-up (2026-09-19)

Run 35447641798 (API 29 `google_apis` 320x640 and API 36 `aosp_atd`) failed three
P0-07 instrumented contracts. The product/test fixes below keep P0-07 `todo`;
they are not a step completion.

- `NavigationBackTest.backFromListFinishesActivity` — back from the list **does**
  finish `MainActivity` (P0-07-R3). Espresso `pressBack()` throws
  `NoActivityResumedException` when that happens, so the test now uses
  `pressBackUnconditionally()` and asserts `DESTROYED`.
- `AccessibilitySemanticsTest.screensMirrorCorrectlyUnderForcedRtl` — clickable
  list rows merge the source label into the row node, so measuring
  `onNodeWithText("Imported")` compared delete against the **full row** and
  could never pass. The test now measures the unmerged `source_row_label` and
  Delete icon. The row still uses `Arrangement.spacedBy(12.dp)` so those
  unclipped bounds stay separated under RTL.

Connected re-run of those two classes is required on the hosted API 29/36 legs;
this environment does not claim those counts here.

Local static/JVM verification on this follow-up (2026-09-19), no emulator:

- `scripts/plan-check.sh` — `steps=77 errors=0`
- `./gradlew detekt lint :app:test :vault:test assembleDebug` — `BUILD SUCCESSFUL`
- `:app:testDebugUnitTest` 33 passed, 0 failures; `:vault:testDebugUnitTest` 68 passed,
  0 failures
- `:app:compileDebugAndroidTestKotlin` — `BUILD SUCCESSFUL`

Hosted run 35450834208 on this follow-up: API 29 app **36/38**, API 36 app
**37/38**. Cleared: `unavailableProviderIsRejectedGracefully` and
`backFromListFinishesActivity` on both APIs. Remaining:
`screensMirrorCorrectlyUnderForcedRtl` (both; merged-row bounds) and
`extractedTextIsInertAndCorrectionIsAttributable` (API 29; dialog label
`assertIsDisplayed`). Those two are addressed in the next commits; connected
counts are not claimed until a later hosted run.
