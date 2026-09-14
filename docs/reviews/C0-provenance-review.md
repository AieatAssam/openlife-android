# Capability 0 provenance review

Per design §5's completion gate: "For C0, prove source bytes and acquisition
metadata only; do not fabricate Fact objects to satisfy the gate." C0 has no
Fact, Record, or Action objects at all — this review demonstrates original
preservation and acquisition metadata only, against the actual schema and
code, not the design document's description of a future capability.

## No fabricated Fact objects

`grep -rln "class Fact\|class Record\|class Action\b" vault/src/main
app/src/main` returns nothing. `OpenLifeDatabase`'s own schema comment states
plainly: "No Fact, Record, or Action tables exist in this schema (design §5,
§9)." `grep -rln "Fact\|extraction\|confidence\|OCR" vault/src/main
app/src/main` returns only false-positive substring matches
(`BitmapFactory`, `artefact`) and the schema comment itself quoted above —
no genuine extraction/confidence/Fact concept exists anywhere in C0's code.

## The Source object matches design §4's field table exactly

`vault/src/main/java/org/openlife/vault/model/Source.kt`:

| Design field | Implementation |
| --- | --- |
| UUID | `id: UUID` |
| digest | `sha256: ByteArray?` (SHA-256, computed once at prepare time) |
| original bytes | referenced via the encrypted artefact file (`<uuid>.blob`), never stored inline in the row — the row holds only metadata and the wrapped key |
| intake metadata | `importedAt`, `intakeKind`, `mimeType`, `byteCount`, `width`, `height`, `orientation`, `artefactVersion` |

No field beyond this table exists on `Source`. No interpretation, extraction,
or derived-value field of any kind is present.

## Original bytes are immutable while retained

- `prepareImport` reads the source exactly once and computes its digest from
  that single read (`BoundedStreamReader`); the stage file is the encrypted
  form of exactly those bytes.
- `saveImport` renames the stage file to its READY blob — a rename, not a
  re-encode or re-write of content. No crop, rotate, or re-compression step
  exists anywhere in the import/save path (confirmed by `grep -rn
  "rotate\|crop\|Matrix(" vault/src/main` returning nothing relevant).
- Verified end-to-end this stage (C0-11):
  `EnvelopeTamperingThroughRepositoryTest.anUntamperedSourceStillRoundTripsAfterTheAboveChecks`
  and the design's own digest-equality tests
  (`ImportRepositoryTest.validJpegIsPreparedAndStagedRowMatchesTheOriginal`,
  `IntakeActivityTest.changedBytesAfterOpenAreNotSilentlySubstituted`) prove
  the saved bytes are exactly the bytes read at prepare time, not a later
  or different read of the same nominal source.
- `orientation` is recorded as separate metadata (currently always
  `Orientation.NORMAL`, since EXIF-derived orientation is explicitly out of
  scope for C0 — `ImportRepository.kt` comment, not silently claimed as
  supported) and is never used to mutate the stored bytes, matching design
  §4's "Record display orientation separately."
- Deletion is the one explicit exception to retention (design §4), never a
  mutation — `DeletionRepository` removes the row and file together; there
  is no code path that modifies a retained Source's bytes in place.

## The digest is not overstated as an authenticity claim

Design §4: "The digest checks byte integrity and detects exact duplicates;
it is not an authenticity certificate or a content-addressed identity."
The actual viewer UI text (confirmed on-device, C0-16/C0-17 passes) reads:
"Integrity: Verified against the saved copy" /
"Verification confirms this is the exact copy OpenLife saved. It does not
confirm who sent it or that its contents are true." — this matches the
design's own required framing verbatim in spirit, not just in the design
document. No UI surface anywhere claims sender identity, authenticity, or
truth of content.

## Acquisition metadata only, no invented content

`IntakeKind` records only how the bytes arrived (`SHARE` or
`PHOTO_PICKER`) — never a claimed filename, sender package, or path (design
§7/§8: "Sender-supplied paths, filenames and claimed package are ignored -
never treated as identity," re-confirmed by `IntakeIntentValidator`'s own
tests, which reject exactly these attempts). `importedAt` is the device's
own clock at prepare time, not a claimed timestamp from the source. No
field on `Source` is derived from image content (no OCR, no color/scene
analysis, nothing) - C0 does not decode the image for anything beyond
format/dimension validation and a sampled preview render.

## Conclusion

C0's provenance surface is exactly what design §4/§5 asks for at this
capability: an immutable Source, a real digest used only for integrity and
duplicate detection, acquisition metadata with no invented content, and no
Fact/Record/Action object of any kind. No gap was found in this review.
