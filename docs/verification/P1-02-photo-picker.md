# P1-02 verification: Photo Picker route hardening

Date: 2026-09-24. Branch: `plan/P1-02-photo-picker`. Device: `dev36` (API 36,
google_apis, `com.google.android.photopicker`). The API 29 fallback route is
exercised on the CI API 29 leg by `pickerContractRequestsASingleImageOnEveryFallbackRoute`.

## Adapting the brief

- **R7 supersedes a design note.** Accepting a wildcard `image/*` intent
  type made the design note's "skip the sender-MIME check for forwarded
  picker intents" and the `IntentShape.isForwardedPickerIntent` flag
  unnecessary. The picker route instead sends `image/*`, and the nonce from
  R6 is what distinguishes it.
- **Test names kept, semantics followed.** The RED test name
  `forwardedPickerIntentWithoutSenderMimeIsAcceptedOnlyWhenMarkedAndExplicit`
  is kept from the brief, but it asserts R7's semantics.
- **Pinned contract version.** The brief expected the contract version to
  be read from source. It resolves to `androidx.activity` 1.13.0, not the
  1.11.0 that `activity-compose` names, and its bytecode was read to record
  the fallback chain in C0-R1.

## RED (5686649)

| Test | Failure |
| --- | --- |
| `IntakeIntentValidatorTest` (3, JVM) | wildcard intent type rejected (F-35); no normalisation or unsupported-format outcome |
| `PickerNonceTest` (3, JVM) | the stub never issued or consumed |
| `PhotoPickerRouteTest.pickerForwardingDoesNotTouchContentResolverOnMainThread` | "forwarding blocked the main thread for 6019ms" (F-02) |
| `PhotoPickerRouteTest.directShareClaimingThePickerIsRecordedAsShare` | recorded PHOTO_PICKER (F-34) |
| `PhotoPickerRouteTest.unsupportedPickedTypeIsExplainedAndNothingIsStaged` | no explanation naming JPEG and PNG |
| `IntakeScreenTest.photoPickerPreviewExplainsCloudProviders` | no cloud-provider copy (F-47) |

These tests passed on the unchanged tree:

- **`pickerRouteIsRecordedAsPhotoPicker`**: the positive control.
- **`pickerContractRequestsASingleImageOnEveryFallbackRoute`**: a
  characterisation of the pinned contract.
- **`noPersistableUriGrantIsEverTaken`**: a guard, proven by mutation.
  Adding `takePersistableUriPermission` to `forwardPickedUri` made it fail.

## GREEN (cc57b08)

- **Forwarding (R1/R2).** MainActivity forwards `image/*` with no
  ContentResolver call on the main thread.
- **Nonce (R6).** `PickerNonce` is a SecureRandom 128-bit value, used once
  and compared in constant time. A wrong guess does not burn the real
  nonce. `EXTRA_INTAKE_KIND` is removed.
- **Provider type (R7).** `IntakeIntentValidator.checkProviderType`
  returns Match (normalised), UnsupportedFormat or Mismatch.
- **Unsupported format (R4).** The rejection copy names JPEG and PNG.
- **Cloud note (R8).** A picked item's preview shows the cloud-provider
  note.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew detekt lint :app:test` | pass |
| `./gradlew :app:connectedDebugAndroidTest` | 82/82 |
| Manual: real system picker, synthetic WebP | "Not imported: OpenLife imports JPEG and PNG images only, and this item is in a different format"; 0 stage files |
| Manual: real system picker, synthetic PNG | preview `PNG · 1×1 · 68 B` plus the cloud-provider note; cancelled |

The synthetic WebP and PNG were generated in this session and deleted from
`/sdcard/Pictures` afterwards.
