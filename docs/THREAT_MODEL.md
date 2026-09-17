# OpenLife threat model

Source of truth: `openlife-design-v0.3.md`, Section 3. This file restates it for
implementation and review reference and records the controls actually built for
Capability 0. Update it in the same change that changes behaviour it describes.

## Enforceable promise and its boundary

The enforceable promise is that OpenLife does not transmit user data or
intentionally expose it through backup, external storage, exported providers or
outbound application handoffs. The app cannot guarantee the behaviour of a
compromised operating system, an authorised screen reader, a malicious
keyboard, or a person photographing the screen. Public wording must explain
this boundary rather than promise protection against every device-level
adversary.

## Controls beyond the INTERNET permission

- All vault files, including the encrypted database and its sidecars, live
  under credential-protected `noBackupFilesDir`.
- `allowBackup=false`, plus explicit legacy backup and modern data-extraction
  exclusions for every app-data domain, covering both cloud backup and device
  transfer. The shipping configuration is tested, not assumed — device-transfer
  behaviour differs across manufacturers and the manifest flag alone is
  insufficient (R2).
- No exported vault `ContentProvider` or `FileProvider` for outward sharing.
  No user data is sent to browser, calendar, messaging, email or share
  intents. No content in logs, clipboard, crash attachments, public caches,
  notifications or recents previews.
- The Photo Picker can expose items supplied by a cloud media provider.
  OpenLife reads only the explicitly selected URI and cannot control that
  provider's download or logging behaviour (R3). This is explained at intake;
  it is not permission for OpenLife to upload or sync its vault. No durable
  URI grants are retained in C0 (no `takePersistableUriPermission`).

## Capability 0 threat matrix

| Threat | Required defence | Residual limit |
| --- | --- | --- |
| Another ordinary app seeks vault content | Private storage, no exported reader, narrow incoming URI grant | OS compromise or an authorised system service is outside this boundary |
| Malicious or misleading shared image | Explicit preview, strict type and size checks, inert display | Import does not establish sender identity or truth |
| Copied storage or accidental backup | Encrypted artefacts and database, Keystore wrapping, backup exclusions | File count and ciphertext sizes may remain observable |
| Corruption or interrupted writes | Authenticated encryption, staged commit, deterministic recovery | Failed media or lost keys may make content unrecoverable |
| Screenshot or shoulder surfing | Secure windows, protected recents, explicit foreground/background content scrubbing, no content notifications | An external camera and compromised OS remain possible |
| Compromised dependency or release | Gradle SHA-256 dependency verification fails on unverified bytes; the release classpath denylist, CycloneDX SBOM, committed licence inventory, and manifest/egress checks make resolved release inputs auditable | Source availability alone does not prove binary integrity; ML Kit's documented transport exception expires at P2-05 |
| Resource exhaustion by a provider | Byte and pixel ceilings, bounded preview, cancellable I/O | In-process native decoding cannot guarantee a hard time bound |

`FLAG_SECURE` is required on all content-bearing windows and dialogs (R6). It
helps prevent supported screenshots and non-secure display output, but is not
universal protection against hostile devices. `MainActivity.onStop()` clears
decoded list thumbnails and returns the viewer to the list; `IntakeActivity`
clears authenticated preview state and re-authenticates its staged UUID on
return. `DisposableEffect` releases decoded viewer/preview bitmaps, and the
thumbnail cache uses a generation barrier so an in-flight decode cannot be
retained after a background transition. Protected recents remain an effect of
`FLAG_SECURE` (`applySecureWindow()`), confirmed via `dumpsys window` showing
no recents-thumbnail bitmap for the task. Every icon-only control carries a
`contentDescription` and large-text rendering (1.3x/2.0x scale) was checked for clipping across every screen
(`docs/verification/C0.md` C0-16); an actual TalkBack accessibility-service
run has not been performed in this environment and remains an open item
(`docs/reviews/C0-security-self-review.md`), not a passed check.

## Access policy limit (owner decision pending)

The C0 access policy relies on the Android device lock and app sandbox; it has
no separate biometric or passcode lock. This does not protect against someone
using an already-unlocked phone. An app-lock requirement must be settled before
distributing C0 to real users, since changing authentication-bound key access
affects the key lifecycle design. Tracked in `docs/decisions/0001-c0-defaults.md`.

## Environment-specific gaps in this build (recorded, not hidden)

- Instrumented and adversarial testing in this repository's development
  environment runs on an Android 16 (API 36) emulator only; no API 29 (the
  declared `minSdk`) system image is installable here (see
  `docs/decisions/0001-c0-defaults.md`). Lowest-supported-API behaviour is
  therefore unverified in this environment and is recorded as an open gap in
  `docs/verification/C0.md`, not silently assumed to pass.
- Device-transfer and manufacturer-specific backup behaviour (R2) cannot be
  fully exercised on an emulator. Emulator-observable backup/data-extraction
  results are recorded; physical-device confirmation remains outstanding.

## References

Numbered references (R1–R9) match Section 16 of `openlife-design-v0.3.md`.
