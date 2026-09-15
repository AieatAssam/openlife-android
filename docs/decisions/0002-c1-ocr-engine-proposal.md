# Decision 0002 — Capability 1 OCR engine proposal

Status: **accepted first slice; owner approved 2026-09-15**.

This record selects the first C1 engine without changing the no-network,
no-telemetry, or preserved-byte C0 boundary. It follows
`docs/capabilities/C1.md`; later local engines remain configurable alternatives
and require their own review before registration.

## Recommended first slice

Use a bundled, offline Latin-script OCR model with region and confidence output,
and support Latin only in the first C1 release. An image containing unsupported
or mixed scripts is reported as unsupported or partially ungrounded; the app
does not silently guess a language or translate text.

The selected engine is the bundled ML Kit Text Recognition artifact
`com.google.mlkit:text-recognition:16.0.1`. Google documents bundled versus
unbundled installation and exposes text-block, line, element, and symbol
regions plus confidence/rotation metadata:

- <https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- <https://developers.google.com/ml-kit/release-notes>

The first implementation records the dependency, packaged asset/native ABI
inventory, peak-memory/performance measurements, and offline behavior in the
C1 verification handoff. The engine is kept behind a local `OcrEngine`
interface so a future fully local alternative can be selected without
changing the persisted provenance contract.

Implementation note: the pinned bundled artifact brings Google transport
classes transitively because the ML Kit client references them at class-load.
OpenLife removes `INTERNET` and `ACCESS_NETWORK_STATE` from both merged app
manifests, does not configure runtime model download, and exposes no cloud or
telemetry call. The transitive transport footprint is recorded for release
review rather than hidden in the dependency inventory.

## Alternatives requiring an explicit change

- Tesseract with a vendored, pinned `eng` traineddata file: stronger
  open-source/auditability story, but requires a native Android binding,
  traineddata provenance, larger assets, and a separate evidence-coordinate
  adapter.
- Another fully local engine/model chosen by the owner, with the same licence,
  ABI, asset, memory, accuracy, and abstention evidence.

No unbundled or runtime-downloaded model is acceptable. No cloud OCR, remote
model, `INTERNET` permission, telemetry, or automatic outbound action may be
introduced.

## Accepted first-slice conditions

The owner approved:

1. Bundled ML Kit Latin `16.0.1`; licence and packaged footprint are reviewed
   as part of the C1 dependency handoff.
2. Latin-only support; unsupported or mixed scripts are reported as
   ungrounded/unsupported and never guessed or translated.
3. C0 byte/pixel limits, a 15-second OCR deadline, 200,000 characters, and
   2,000 spans per revision.
4. `SOURCE_PIXELS` coordinates after orientation, nullable confidence and
   nullable regions when the engine supplies none.
5. Immutable OCR output, separate attributed user corrections, explicit
   review metadata, and cascade deletion with the Source.

## Planned schema once accepted

Add a Room migration from C0 schema v1 to a versioned C1 schema containing only
OCR-derived data:

- `ocr_revisions`: source ID, state, engine/model version, orientation,
  extraction time, and immutable OCR payload metadata.
- `ocr_spans`: revision ID, text, nullable confidence, coordinate system, and
  nullable evidence region when the engine supplies none.
- `ocr_user_revisions`: attributed corrections that reference the OCR span or
  revision without overwriting it.

Foreign-key deletion propagation must remove dependent rows in the same
serialized mutation path as Source deletion. C1 must not add Fact, Record,
Action, search, or reminder tables.

## TDD gate after acceptance

The first implementation increment will begin with failing tests for bounded
OCR cancellation, missing/corrupt Source handling, exact Source-byte
preservation, evidence-coordinate mapping, nullable confidence, correction
attribution, deletion propagation, inert prompt-injection text, and release
dependency/manifest review. Only then will the selected engine and bundled
model be added.
