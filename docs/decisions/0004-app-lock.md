# ADR-0004: Optional app lock as an access gate

Date: 2026-09-24
Status: accepted for P1-07

## Context

The C0 access policy relied on the Android device lock alone, so anyone
holding an already-unlocked phone could open OpenLife. Design §3 required this
to be settled before real-user distribution. Binding the Keystore wrapping key
to user authentication would also protect the key, but a credential change or
removal can permanently invalidate such keys. That would make the whole vault
unrecoverable, which design §10 treats as the outcome to avoid.

## Decision

- **Opt-in and off by default.** When on, OpenLife gates every
  content-bearing screen with `BiometricPrompt`. API 30+ allows
  `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`. API 29 allows
  `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`, because STRONG plus the credential is
  unsupported there. No negative button is set, and OpenLife has no PIN of its
  own: a home-grown PIN store would be a second, weaker credential.
- **No key binding.** Keystore keys are **not** bound to authentication
  (`setUserAuthenticationRequired` stays false). The lock is an access gate,
  and the copy says so: items are encrypted the same way with it on or off.
- **Stored settings.** The policy (`enabled`, timeout in {0, 30, 300, 900}
  seconds, default 0 when enabled) lives in the private `app_lock` preferences
  file. Vault reset clears it (ADR-0005).
- **When it locks.** The lock engages on process start and after the process
  has been in the background at least the timeout.
  - Background is tracked with `ProcessLifecycleOwner` and measured on
    `SystemClock.elapsedRealtime()`. That clock keeps counting through device
    sleep; `System.nanoTime()` and `uptimeMillis()` stop during deep sleep,
    and the wall clock can be changed.
  - With timeout 0 the lock engages at the background transition itself:
    when `ProcessLifecycleOwner` reports the process stopped, about 700 ms
    after the last activity stops. Content is not exposed in that window,
    because each activity scrubs its content in its own `onStop` and
    `FLAG_SECURE` keeps it out of Recents. There is no frame on return in
    which old content can recompose. (Amended by P1-19-R3: an earlier
    wording implied the lock engaged at the activity's own stop.)
- **Trips OpenLife starts itself.** The system credential screen and the
  Photo Picker are other apps' activities that OpenLife opens and expects to
  return from. That round trip is not a background period. Without this
  exemption, a PIN entry on API 29, or picking a photo, would relock the app
  behind the user.
  - **Bounded (P1-19-R1, amended 2026-11-24).** The time away is still
    measured during such a trip. On return, the lock engages if the trip
    lasted at least the longer of the configured timeout and 60 seconds
    (`AppLockState.EXTERNAL_GRACE_MILLIS`). Originally the exemption had no
    bound, so a trip whose result never arrived left the lock off until the
    process died (audit A-01, A-07).
  - **The trip ends on return (review Q-02, 2026-11-25).** `onForeground`
    clears the trip flag before deciding, so a picker result that never
    arrives cannot carry the grace into later, ordinary background periods.
    The legitimate round trip is unaffected: its background period was
    already classified as a trip when it began.
  - The accepted residuals: within that window, someone else could pick up
    the phone and return to OpenLife unprompted. A return within the ~700 ms
    process stop delay produces no foreground event, so if that trip's
    result is also dropped, the next background period still gets the grace.
- **Enforced twice.**
  - While locked, `AppLockGate` composes no content-bearing composable.
  - Every UI path that decrypts item bytes or opens a shared stream also
    awaits `AppLockController.awaitContentAccess()`: list thumbnails, viewer,
    intake stream, staged-preview restore and OCR. A composition mistake
    therefore still cannot read bytes while locked.
- **Cannot verify stays locked.** Any `canAuthenticate` result other than
  success, and any prompt error other than a cancel, means "cannot verify":
  content stays locked, and the screen explains how to set a screen lock. The
  lock never opens silently. Enabling the lock is refused, with an explanation,
  when nothing can verify the user.
- **Where it is offered.** It is offered once, right after the first-run
  explanation in the main app, and can be changed in Settings.
  - When first run happens inside a share, the offer is skipped so the share
    is not interrupted.

## Consequences

- **Removing the screen lock locks the user out.** The user must restore a
  screen lock to see their items again (or reset the vault). This is the
  honest consequence of "never unlock silently", and the lock screen says so.
- **`USE_BIOMETRIC` is the only new permission.** It is a normal permission.
  The library's legacy `USE_FINGERPRINT` applies only below API 28, which is
  below minSdk 29, so it is removed from the merged manifest.
  `ManifestBoundaryTest` pins the exact set.
- **New dependency.** `androidx.biometric:biometric:1.1.0` is the latest
  stable release. Both activities become `FragmentActivity`, which
  `BiometricPrompt` requires.

## Revisit when

- **Auth-bound keys.** Revisit only with a tested key-migration path, so that
  a credential change cannot lose the vault (P9-02).
- **Notification-opened screens.** Before P4 adds notification-opened detail
  screens, route them through the same gate. This is P1-07-R2's P4 clause.

## Amendment 2026-11-27 (ADR-0019, P1-21)

The minimum Android version is now 26 (ADR-0019). The "API 29 allows
`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`" statement above now covers API 26-29: the
lower bound moves from 29 to 26, and the STRONG/WEAK split is unchanged
(STRONG plus the device credential from API 30). The `USE_FINGERPRINT`
statement in Consequences is superseded: the app manifest restores
`USE_FINGERPRINT` with `maxSdkVersion="27"`, so it is present in the merged
release manifest for API 26-27 only. `USE_BIOMETRIC` remains the only permission
for API 28+. Device behaviour on API 26-29 is unverified (see
`docs/verification/P1-21-min-sdk-26.md`).
