# Facts evaluation set v1 (P3-06)

Synthetic text cases for the ADR-0008 typed extractors (`rules-*-v1`). Every expected output is
written from ADR-0008 and its amendments, never from the extractor's current output. No real
personal data and no copied documents are used.

## Files

- `text.tsv`: 200 JVM text cases. Eight tab-separated columns: id, kind, text, items, abstain,
  truth, tags, ref. Items and abstentions are separated by ` ‖ `. Each item is `KIND¦raw¦canonical`
  (an abstention is `KIND¦raw`, and `*` means no kind). `truth` is set only for d/m ambiguous cases.
  Raw text is matched as an exact slice of the span. Invisible and dash characters are written as
  `\uXXXX` escapes (BMP only).
- `image-cases.tsv`: 30 image cases. Each row is `img-NN<TAB>ROW+ROW`: the text-set rows the image
  draws, one per line, in order. Rendered by `./gradlew :eval:generateFactsImages`.
- `images/`: per case `img-NN.png` (host-rendered with the OCR fixture renderer and IBM Plex),
  `img-NN.json` (truth: lines, items, abstentions, truth), a `gate.json` copy and `manifest.tsv`
  with the SHA-256 of every file. Packaged into the androidTest APK only.
- `gate.json`: the R2 thresholds. `FactsGatePinTest` pins the numbers and `FactsEvalTest` enforces
  them.

## Scope

- In scope: the JVM text part (`:vault:test`, every change), and the 30 image cases through OCR
  (`FactsImageEval`, instrumented, `@Slow`, opt-in `p306FactsImage`, run on the tablet).
- Not in scope: the DB, FTS and `FactExtractionCore` layers. The text part scores only the pure
  extractor output (`FactExtractor.extract`). The image part runs the OCR engines, then
  `FactExtractor.extract` over the OCR spans. It does not use the repository path (see
  `docs/verification/facts-eval.md`).

## Families and gating

DATE covers DATE, TIME and DATETIME (decision Q-3). MONEY, URL, EMAIL and PHONE are gated
separately. Metric definitions and the measured results are in `docs/verification/facts-eval.md`.
