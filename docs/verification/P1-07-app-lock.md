# P1-07 verification: optional app lock

Date: 2026-09-24. Branch: `plan/P1-07-app-lock`. Device: `dev36` (API 36).
API 29 evidence comes from CI on the PR.

## RED (7dc43d8)

| Test | Failure on the unchanged tree |
| --- | --- |
| `AppLockStateTest` (6, JVM) | `NotImplementedError` from the stub |
| `AppLockFlowTest.lockedMainActivityComposesNoContentAndNoBytesAreLoaded` | lock screen not shown |
| `AppLockFlowTest.shareIntentWhileLockedOpensStreamOnlyAfterUnlock` | lock screen not shown |
| `AppLockFlowTest.enablingWithoutEnrolledCredentialIsRefusedWithExplanation` | no "App lock" entry in Settings (`NullPointerException` on the lookup) |
| `AppLockFlowTest.credentialRemovedWhileLockEnabledStaysLockedWithInstructions` | no cannot-verify explanation |
| `FirstRunFlowTest.acknowledgingFirstRunShowsTheListWithoutMainThreadDiskAccess` | "the app lock was not offered after the explanation" |

`ManifestBoundaryTest.releasePermissionsAreExactlyTheAllowlist` passed once
the pinned dependency and its `USE_FINGERPRINT` removal were in. Its mutation
proof: dropping the removal made it fail with
`but was:<[USE_BIOMETRIC, USE_FINGERPRINT, DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION]>`.
The removal was then reverted.

The first instrumented RED run failed on the `TODO()` test hooks instead of
the assertions. Those hooks became no-ops and the run was repeated; the
failures above are from the second run.

During RED a "System UI isn't responding" dialog held focus on dev36 and hid
the first-run screen. It was dismissed with Wait before the recorded run.

## GREEN (95c9281)

- `AppLockState` is pure and uses an injected clock (`SystemClock.elapsedRealtime`
  in production). `AppLockController` feeds it `ProcessLifecycleOwner`
  transitions, persists the policy, and provides `awaitContentAccess()`.
- `AppLockGate` composes content only while unlocked, and `UNKNOWN` keeps the
  splash up. `MainActivity` content moved into `UnlockedContent` with its own
  NavController, and the deep-link request is consumed there after unlock.
- Every decrypt and stream-open path awaits `awaitContentAccess()`: list
  thumbnail, viewer, intake stream, preview restore (including process
  recreation) and OCR.
- `BiometricPromptAuthenticator` handles authenticators per API level.
  Anything other than `BIOMETRIC_SUCCESS` makes the device "cannot verify".
- Settings > App lock refuses to enable without a screen lock and offers
  fixed timeout choices. The offer after first run appears in the main app
  only.
- A completed vault reset also turns the in-memory lock off, since the reset
  clears the `app_lock` file.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | pass (`AppLockStateTest` 6/6, `ManifestBoundaryTest`, updated source-text `NavigationBackTest`) |
| `AppLockFlowTest` (connected) | 4/4 |
| `./gradlew :app:connectedDebugAndroidTest` | 88/88 |
| `./gradlew detekt lint :app:test :vault:test assembleDebug assembleRelease` | BUILD SUCCESSFUL |

Vault code is unchanged; CI runs the vault suite.

## Hosted CI follow-ups

- **Run 35990662858: brand golden.** Both device legs failed only
  \`BrandScreenshotInstrumentedTest\`, because the first-run copy changed.
  - The CI-profile first-run goldens were regenerated with the workflow's
    \`generate-goldens\` dispatch (run 35991975064), reviewed and committed
    (8a35aac).
  - The local 420 dpi profile crops above the changed paragraph and is
    unchanged.
- **Run 35992588109: release smoke.** \`CONNECTED_TESTS_RAN\` was app=88 and
  vault=115 on both legs, but the release smoke timed out on a fresh install.
  After "I understand" it now meets the one-time app-lock offer.
  - \`scripts/release-smoke.sh\` taps "Not now" when the offer appears.
  - Local run on dev36: \`RELEASE_SMOKE_RESULT=pass\`.

### Manual: real BiometricPrompt, device-credential path (dev36, API 36)

The transcript below uses real `BiometricPrompt` with a PIN set through
`locksettings`. The PIN was cleared at the end and the app data was cleared
(`pm clear`), so the shared emulator is back to its original state.

```
$ am start (fresh install)
Status: ok
# offer screen:
text="Lock OpenLife too?"
text="Anyone who can unlock this phone can open OpenLife. App lock asks for your fingerprint, face or screen lock again before OpenLife shows anything. It is an access gate, not extra encryption. You can change this later in Settings."
text="Turn on app lock"
text="Not now"
$ tap "Turn on app lock" (no screen lock set)
text="Lock OpenLife too?"
text="Anyone who can unlock this phone can open OpenLife. App lock asks for your fingerprint, face or screen lock again before OpenLife shows anything. It is an access gate, not extra encryption. You can change this later in Settings."
text="App lock needs a screen lock. Set one in the system Settings app first, then turn app lock on here."
text="Turn on app lock"
text="Not now"
$ adb shell locksettings set-pin 1234
Pin set to '1234'
$ tap "Turn on app lock"
text="Nothing kept yet"
text="Import a screenshot, receipt or letter you want to keep. OpenLife saves its own encrypted copy on this device only. You can also share an image to OpenLife from another app."
text="Import from photos"
$ input keyevent HOME; sleep 3; am start
Status: ok
# after return:
content-desc="Tap to cancel authentication"
text="Unlock OpenLife"
content-desc="PIN area"
$ input text 1234; input keyevent ENTER
# after PIN:
text="Nothing kept yet"
text="Import a screenshot, receipt or letter you want to keep. OpenLife saves its own encrypted copy on this device only. You can also share an image to OpenLife from another app."
text="Import from photos"
text="OpenLife"
$ input keyevent HOME; am start (timeout 0: locks again)
Status: ok
content-desc="Tap to cancel authentication"
text="Unlock OpenLife"
content-desc="PIN area"
$ back (cancel prompt)
text="OpenLife is locked"
text="Use your fingerprint, face or screen lock to see what you have kept."
text="Unlock"
$ adb shell locksettings clear --old 1234
Lock credential cleared
true
# credential cleared while lock enabled; $ HOME; am start
Status: ok
text="OpenLife can't verify you"
text="This device has no screen lock or fingerprint that OpenLife can use right now, so your items stay locked. Set a screen lock in the system Settings app, then come back and choose Check again."
text="Check again"
$ am force-stop; am start (process start)
Status: ok
text="OpenLife can't verify you"
text="This device has no screen lock or fingerprint that OpenLife can use right now, so your items stay locked. Set a screen lock in the system Settings app, then come back and choose Check again."
text="Check again"
$ pm clear org.openlife (restore emulator state)
Success
$ locksettings get-disabled: true
```

What the transcript shows:

- Without a screen lock, the offer refuses with an explanation.
- With a PIN set, the system prompt "Unlock OpenLife" appears after returning
  from Home.
- The correct PIN unlocks the app with no relock loop.
- Timeout 0 relocks on the next return.
- Cancelling the prompt leaves the fixed-copy lock screen.
- With the PIN removed, the app stays on "OpenLife can't verify you", also
  after a process restart.

## Gaps (not assumed)

- **Fingerprint path:** not exercised manually. Enrolling a fingerprint
  through the emulator's Settings UI with `adb emu finger touch` was not
  done on the shared emulator. The prompt, and our handling of its result
  callbacks, is the same code path as the credential one.
- **API 29:** the WEAK|CREDENTIAL combination and the Keyguard credential
  activity round trip are covered only by the CI API 29 leg of
  `AppLockFlowTest`, which uses the scripted authenticator. A real-prompt run
  on API 29 is an owner/physical-device action.
- **Rotation during an open prompt:** after rotation the callback may be lost,
  and the user taps Unlock again. This fails closed; it is not tested.
