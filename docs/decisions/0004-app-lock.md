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
  - With timeout 0 the lock engages at the background transition itself, so
    there is no frame on return in which old content can recompose.
- **Trips OpenLife starts itself.** The system credential screen and the
  Photo Picker are other apps' activities that OpenLife opens and expects to
  return from. That round trip is not a background period. Without this
  exemption, a PIN entry on API 29, or picking a photo, would relock the app
  behind the user.
  - The accepted residual: while the picker is open, someone else could pick
    up the phone and return to OpenLife unprompted.
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
