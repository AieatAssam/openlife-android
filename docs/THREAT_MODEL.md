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
  - **Clipboard and keyboard (P1-19-R5, review Q-01).** Every text field
    that can hold user content is `ContentTextField`. Closed, as established
    from the compiled Compose foundation 1.12 classes
    (docs/verification/P1-19-app-audit-remediation.md):
    - The field's `LocalClipboard` drops every write, so no route the field
      offers can put its text on the clipboard. That covers menu Copy/Cut,
      shortcuts, the keyboard's copy/cut requests and the accessibility
      Copy/Cut actions. Paste still reads the clipboard.
    - The context menu is an allow-list of Paste and Select all. Copy, Cut,
      Autofill, ACTION_PROCESS_TEXT apps (Translate and the like) and
      text-classification actions are not offered.
    - Smart selection is off app-wide, so selected text is not sent to the
      system TextClassifier.
    - Copy/Cut shortcuts and the keyboard's copy/cut requests are refused
      before they reach the field.
    - Autocorrect is off, and the keyboard is asked not to learn
      (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
    - **Autofill (review U-01).** Compose reports every text field to the
      autofill framework by default: on focus it calls `notifyViewEntered`,
      on each edit `notifyValueChanged` with the text, and it writes the
      text into the structure an autofill service receives. FLAG_SECURE does
      not stop this. The content field now declares no autofill data type
      (`ContentDataType.None`), so Compose sends neither call for it, and
      marks its data sensitive, so its value is stripped from fill requests.
      Every activity window, and the dialog window the correction field
      opens in, is marked not important for autofill with its descendants.
      Implemented; unverified (gap — pending physical-device run).
    A boundary test fails on any other text field in the app, and on any
    clipboard reference outside `ContentTextField`. The vault-reset word
    field is the one named exemption, because it holds only a fixed
    confirmation word.
  - **Residual (not closed):**
    - The keyboard can read the whole field, not only what is typed: an
      IME reads the prefilled OCR text through the InputConnection
      (`getExtractedText`, `getTextBeforeCursor`). The no-learning flag is
      a request, and a keyboard that ignores it can keep that text.
    - A screen reader reads the field aloud, by design.
    - An accessibility Cut action removes the selected text from the field,
      though nothing reaches the clipboard, so the cut text is lost from the
      field (review U-04: kept and documented; see the verification record).
    - An autofill service that asks the platform to include views not
      important for autofill, or a platform that ignores the flag, is not
      covered by the window exclusion; the field-level settings still apply.
    - None of this is device-verified yet; the instrumented
      `ContentTextFieldTest` is pending.
- **Incoming share grants (P1-19-R18).** The sender sets
  `FLAG_GRANT_READ_URI_PERMISSION`, so its presence is not a control.
  OpenLife's defence is that it holds no media or storage permission: it
  can read a shared URI only if the sender really granted it. A share whose
  authority names OpenLife itself is refused. The check compares the host
  only, after removing userinfo and port, so `x@org.openlife...` cannot
  bypass it.
- **Open-source requests (P1-19-R17).** The launcher activity is exported.
  It opens a specific item only when the request carries a single-use,
  in-process nonce that OpenLife's own "Open existing" button issued. Any
  other caller just opens the list.
- The Photo Picker can expose items supplied by a cloud media provider.
  OpenLife reads only the explicitly selected URI and cannot control that
  provider's download or logging behaviour (R3). This is explained at intake:
  a picked item's preview says it may come from a cloud photo service that
  OpenLife does not control, and that OpenLife never uploads (P1-02). It is
  not permission for OpenLife to upload or sync its vault. No durable URI
  grants are retained (no `takePersistableUriPermission`, enforced by a
  source-scan test), including on the contract's `ACTION_OPEN_DOCUMENT`
  fallback. The recorded intake route is not caller-controlled: only
  OpenLife's own picker forwarding, proven by a single-use in-process
  nonce, is recorded as `PHOTO_PICKER`.

## Capability 0 threat matrix

| Threat | Required defence | Residual limit |
| --- | --- | --- |
| Another ordinary app seeks vault content | Private storage, no exported reader, narrow incoming URI grant | OS compromise or an authorised system service is outside this boundary |
| Malicious or misleading shared image | Explicit preview, strict type and size checks, inert display | Import does not establish sender identity or truth |
| Copied storage or accidental backup | Encrypted artefacts and database, Keystore wrapping, backup exclusions | File count and ciphertext sizes may remain observable |
| Corruption or interrupted writes | Authenticated encryption, staged commit, deterministic recovery | Failed media or lost keys may make content unrecoverable |
| Screenshot or shoulder surfing | Secure windows, protected recents, explicit foreground/background content scrubbing, no content notifications | An external camera and compromised OS remain possible |
| Compromised dependency or release | Gradle SHA-256 dependency verification fails on unverified bytes; the release classpath denylist, CycloneDX SBOM, committed licence inventory, and manifest/egress checks make resolved release inputs auditable | Source availability alone does not prove binary integrity; ML Kit's documented transport exception expires at P2-05 |
| Resource exhaustion by a provider | Byte and pixel ceilings, bounded preview, and a 15s provider-read deadline checked between 64 KiB chunks; the owner coroutine closes the descriptor in `finally` on its single-thread provider dispatcher, while an independent deadline+5s watchdog may close only a provider read that never returns | A provider that ignores close can still delay the hard stop; the watchdog's cross-thread close is a documented last resort because concurrent descriptor close has platform/provider hazards; in-process native decoding cannot guarantee a hard time bound |

**Key loss.** The Keystore wrapping key protects the database secret and
every item's key. Android can remove it, for example when the lock screen is
removed or the app data is restored onto another device. OpenLife then reports
the vault as unavailable (`KEY_UNWRAP_FAILED`) and keeps the key file,
database and artefacts exactly as they are. A key is created only by a fresh
bootstrap, where no key file and no database exist yet. Wrap and unwrap never
generate a replacement (`MissingWrappingKeyException`), because a new key
could not decrypt anything and would hide the loss. Everything wrapped under
a lost key is unrecoverable; there is no escrow or cloud copy. Reset is the
only way forward, and it is explicit and destructive (P1-14).

`FLAG_SECURE` is required on all content-bearing windows and dialogs (R6).
P0-07 applies the shared `applySecureWindow()` policy from both activities and
keeps the static assertion in `SecureWindowTest` alongside the manifest
predictive-back check. It
helps prevent supported screenshots and non-secure display output, but is not
universal protection against hostile devices. In multi-window mode an activity can be visible
without focus (STARTED, not RESUMED). Content is therefore scrubbed on
`onStop` (hidden), never on `onPause` (focus lost), and a split-screen
partner app sees only what a screenshot-protected window shows (P1-16,
`MultiWindowScrubTest`). `MainActivity.onStop()` clears
decoded list thumbnails and returns the viewer to the list; `IntakeActivity`
clears authenticated preview state and re-authenticates its staged UUID on
return. `DisposableEffect` recycles the decoded viewer/preview bitmap, whose
only owner is that screen, and the viewer zeroes its authenticated plaintext
once decoded. Cached list thumbnails are dropped, not recycled, on eviction,
trim and backgrounding, because a row still on screen may draw the same
instance (F-28, P1-13); their pixel memory is released when the bitmap is
garbage collected, so it can outlive the drop for an unspecified time. The
thumbnail cache uses a generation barrier so an in-flight decode cannot be
retained after a background transition. Protected recents remain an effect of
`FLAG_SECURE` (`applySecureWindow()`), confirmed via `dumpsys window` showing
no recents-thumbnail bitmap for the task. Every icon-only control carries a
`contentDescription`; the pre-P0-07 large-text baseline (1.3x/2.0x scale) was
checked for clipping across every screen (`docs/verification/C0.md` C0-16).
P0-07 adds deterministic Compose contracts for 1.3x/2x font scale, forced RTL,
and inset bounds, while its final-tree connected run remains a documented
device gap (`docs/verification/P0-07-ui-foundation.md`). An actual TalkBack accessibility-service
run has not been performed in this environment and remains an open item
(`docs/reviews/C0-security-self-review.md`), not a passed check.

## Explicit vault reset

Reset is available in Settings after the user types `RESET` and confirms a
second time. A durable `RESET_IN_PROGRESS` marker is written and synced before
the database is closed or recognised vault files are removed. Bootstrap stops
while the marker exists; retrying reset resumes the same ordered deletion and
removes the marker last. The operation is serialized against imports, clears
registered plaintext UI state, deletes database sidecars and the wrapped key,
then attempts to delete the Keystore alias. It also clears `app_lock`
preferences while retaining the first-run acknowledgement. In C0 there are no
scheduled reminders; P4-04 must add alarm and notification cancellation to
this reset path.

Reset removes live app-owned vault files and is not forensic erasure. Database
corruption can make every saved source unrecoverable because per-source DEKs
exist only in the encrypted database; there is no separate DEK file or backup.
An unavailable-vault screen explains the cause and offers retry only for
retryable causes. Its Settings link reaches the separately confirmed reset;
no error path silently deletes files or creates a replacement key.

## Access policy (resolved by ADR-0004, P1-07)

By default, the access policy relies on the Android device lock and app
sandbox. An optional app lock gates every content-bearing screen with
BiometricPrompt, using a biometric or the device credential, until the user
authenticates.
- **When it locks:** on process start, and after a configurable time in the
  background, measured on a monotonic clock that keeps counting through sleep.
  - With timeout 0, it locks when `ProcessLifecycleOwner` reports the process
    stopped, about 700 ms after the last activity stops. Content is not
    exposed in that window: each activity scrubs its content in its own
    `onStop`, and `FLAG_SECURE` keeps it out of Recents and screenshots.
- **While locked:** no content is composed, and no item bytes are decrypted or
  shared streams opened.
- **What it protects against:** someone using an already-unlocked phone.
- **What it does not add:** cryptographic protection. Keystore keys are not
  bound to authentication, so a credential change cannot make the vault
  unrecoverable.
- **Limits:**
  - It does not protect against anyone who knows the device credential.
  - It does not protect against a compromised OS.
  - A trip to an activity OpenLife started itself (the Photo Picker, the
    system credential screen) is not re-gated on return if it took less than
    the longer of the timeout and 60 seconds (P1-19-R1). Within that window,
    someone else who picks up the phone can return to OpenLife unprompted.
    A longer trip locks on return, even if the picker's result never
    arrives. Returning to the foreground ends the trip (review Q-02), so a
    dropped picker result no longer extends the grace to later background
    periods. Residual: a return within ProcessLifecycleOwner's ~700 ms stop
    delay sends no foreground event; if the result is also dropped, the
    next background period still gets the trip grace. JVM-tested; the
    device test is pending (audit A-07).
- **If the device can no longer verify the user** (screen lock removed):
  OpenLife stays locked and explains how to restore a screen lock. It never
  unlocks silently.

## Authentication versus transient failure (P1-18)

Only a failed GCM tag (`AEADBadTagException`) or a framing or digest
mismatch is evidence that stored bytes were altered, and only that marks an
item `CORRUPT`. Every other Keystore error (for example
`IllegalBlockSizeException` caused by `android.security.KeyStoreException`)
is transient: the item stays READY and the user can retry. A `CORRUPT` item
whose bytes fully authenticate in Settings > Verify all items is restored to
READY. A false transient costs a retry; a false `CORRUPT` would cost the
user's original.

Power-loss durability (V-16, review R-03): sqlcipher-android 4.19.0 sets
`synchronous=NORMAL` on every connection it opens. Under NORMAL in WAL mode
a commit that has already returned can be rolled back by power loss. That
is not only lost metadata: a READY commit the user saw as saved could come
back STAGED, and recovery would then delete the item as an abandoned
import. The vault therefore sets `PRAGMA synchronous=FULL` on the writer
connection in Room `onOpen` (after SQLCipher's own settings), on every
open and in both WAL and TRUNCATE journal modes. The value observed on a
device (`DatabasePragmaTest`) and the write-latency cost of FULL are
pending a physical-device run.

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
