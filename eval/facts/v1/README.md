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
- `gate.json`: the R2 thresholds. `FactsGatePinTest` pins the numbers and `FactsEvalTest` enforces
  them.

## Scope

- In scope: the JVM text part (`:vault:test`, every change).
- Not in scope: the 30 image cases. They wait for the OCR gate to be measured (decision Q-12; the
  OCR gate is NO_MEASUREMENT in `docs/verification/ocr-eval.md`).
- Not in scope: the DB, FTS and `FactExtractionCore` layers. This set scores only the pure extractor
  output (`FactExtractor.extract`).

## Families and gating

DATE covers DATE, TIME and DATETIME (decision Q-3). MONEY, URL, EMAIL and PHONE are gated
separately. Metric definitions are in `docs/verification/P3-06-facts-eval.md`.
