# P0-07 verification: UI foundation

Date: 2026-09-17  
Branch: `plan/P0-07-ui-foundation`  
Status: implementation complete; handoff pending owner review

## TDD record

- RED `c65ab24`: the seven new contract tests failed for the intended missing
  navigation, inset, secure-window, RTL, large-font, and hardcoded-string
  contracts; 32 JVM tests were discovered.
- GREEN `eb12680`: added the theme, resources, navigation host, adaptive icon,
  splash theme, secure-window policy, and activity/screen wiring.
- REFACTOR `899ec4e`: corrected Detekt/Kotlin formatting and split the viewer
  into focused top bar, content, and correction-dialog composables without
  changing the UI contract.

## Local verification

| Command | Result |
| --- | --- |
| `./gradlew :app:detekt` | pass; `BUILD SUCCESSFUL`, 0 findings |
| `./gradlew :app:testDebugUnitTest --rerun-tasks` | pass; `BUILD SUCCESSFUL`; 32 tests, 0 failures, 0 errors |
| `./gradlew :app:compileDebugAndroidTestKotlin` | pass; `BUILD SUCCESSFUL`; the added 400x700dp/48dp inset assertion compiles |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | pass after REFACTOR/final lint fixes; `BUILD SUCCESSFUL`, 114 actionable tasks; app/vault JVM tasks, Detekt, lint, and debug assembly passed |
| `git diff --check` | pass after generated-report cleanup |

The unit contracts cover typed Navigation Compose routes, back-stack shape,
edge-to-edge/inset declarations, secure windows, resource-only UI strings,
large-font coverage hooks, and forced RTL coverage. `AccessibilitySemanticsTest`
also contains Compose checks for 2x font scale, accessible actions, privacy
descriptions, and RTL layout direction.

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
