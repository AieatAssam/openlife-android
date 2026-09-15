# Decision 0002 — Capability 1 OCR engine proposal

Status: **proposal; owner acceptance required before implementation**.

This record narrows the C1 choices without adding a runtime, model, network
permission, or schema ahead of acceptance. It follows
`docs/capabilities/C1.md` and does not change the accepted C0 boundary.

## Recommended first slice

Use a bundled, offline Latin-script OCR model with region and confidence output,
and support Latin only in the first C1 release. An image containing unsupported
or mixed scripts is reported as unsupported or partially ungrounded; the app
does not silently guess a language or translate text.

The current leading candidate is the bundled ML Kit Text Recognition artifact
`com.google.mlkit:text-recognition:16.0.1`. Google documents bundled versus
unbundled installation and exposes text-block, line, element, and symbol
regions plus confidence/rotation metadata:

- <https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- <https://developers.google.com/ml-kit/release-notes>

This candidate is **not selected yet**. Its distribution terms, exact packaged
asset size, native ABI footprint, peak memory, and offline behavior on the
declared API matrix must be reviewed before adding it.

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

## Acceptance conditions before code

The owner must approve:

1. Engine/model and licence, including the exact pinned artifact or vendored
   model hash.
2. Supported scripts/languages for C1-R2, plus behavior for unsupported and
   mixed-language images.
3. Measured limits: source bytes and pixels inherited from C0, OCR decode
   budget, wall-clock cancellation deadline, peak memory, and maximum derived
   text/spans per revision.
4. Evidence coordinates: source pixel coordinates after the C0 orientation
   transform, with nullable confidence and no fabricated regions.
5. Correction semantics: OCR output is immutable; a user correction is a
   separate attributed revision; accepting/rejecting a revision and deleting
   its Source have explicit state transitions.

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
