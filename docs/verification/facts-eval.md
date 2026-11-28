# Facts evaluation: metrics (eval/facts/v1, JVM text part)

Step P3-06. Measured by `FactsEvalTest.gateThresholdsAreMet` on `:vault:test`
against `eval/facts/v1/text.tsv` (200 synthetic text cases). Rules measured:
`rules-date-v1, rules-time-v1, rules-datetime-v1, rules-money-v1, rules-url-v1,
rules-email-v1, rules-phone-v1` (`FactExtractor.RULES_VERSION`). Source of the
numbers: `vault/build/facts-eval/summary.tsv`, written by the test run (2026-11-28).

Scope and gaps:
- The 30 image cases (R1) are not in this set. They wait for the OCR gate
  (decision Q-12). `docs/verification/ocr-eval.md` records the OCR gate as
  NO_MEASUREMENT.
- Expectations come from ADR-0008 and its amendments, not from extractor output.

## Gate (P3-06-R2)

| Gate | Threshold | Measured | n | Result |
| --- | --- | --- | --- | --- |
| DATE precision (DATE, TIME, DATETIME; Q-3) | >= 0.97 | 1.0000 | 54/54 | pass |
| DATE recall (DATE, TIME, DATETIME) | >= 0.85 | 1.0000 | 54/54 | pass |
| MONEY precision | >= 0.95 | 1.0000 | 34/34 | pass |
| URL precision | >= 0.95 | 1.0000 | 10/10 | pass |
| EMAIL precision | >= 0.95 | 1.0000 | 8/8 | pass |
| PHONE precision | >= 0.95 | 1.0000 | 10/10 | pass |
| Ambiguous d/m flagged CLARIFICATION_NEEDED | = 1.0 | 1.0000 | 11/11 | pass |
| Currency inferred from a bare symbol | = 0 | 0 | 0 | pass |

Gate status: met on the JVM text part. The image part and C2 acceptance wait
for the OCR gate (Q-12).

## Per-family and per-rule metrics (informational, not gated)

| Metric | Key | Measured | Note |
| --- | --- | --- | --- |
| Recall | MONEY | 34/36 = 0.9444 | 2 silent misses, both in M-20 (known deviation D-1) |
| Recall | URL | 10/10 | |
| Recall | EMAIL | 8/8 | |
| Recall | PHONE | 10/10 | |
| Precision by rule | rules-date-v1 | 29/29 | |
| Precision by rule | rules-time-v1 | 19/19 | |
| Precision by rule | rules-datetime-v1 | 6/6 | |
| Precision by rule | rules-money-v1 | 34/34 | |
| Precision by rule | rules-url-v1 | 10/10 | |
| Precision by rule | rules-email-v1 | 8/8 | |
| Precision by rule | rules-phone-v1 | 10/10 | |
| Wrong actionable dates | DATE family, definite, not exact | 0 | |
| Abstention expected | expected abstentions matched by (kind, raw) | 49/50 | one missed: T-14 (deviation D-2) |
| Unlisted abstentions | abstentions on rows that list none | 23 | conservative; not wrong values (see below) |
| Raw-slice mismatches | candidate raw is not the text slice at its offsets | 0 | |
| Candidate outcomes | EXACT / SUBSET / SUPERSET / WRONG_VALUE / UNEXPECTED | 116 / 0 / 0 / 0 / 0 | |
| Expected-item status | EXACT / WRONG / ABSTAINED / SILENT | 116 / 0 / 0 / 2 | |

Unlisted abstentions (23): M-20 (one span), U-10 to U-14, E-05 to E-11, E-15,
P-14, P-15, N-09 to N-11, N-21 to N-24. Each row lists no expected item and
no expected abstention, so no candidate was expected there. The extractor
abstains instead of emitting. These are not counted as wrong values. Their
full list is in `vault/build/facts-eval/rows.tsv` (`UNEXPECTED-ABSTAIN`).

## Metric definitions

- **Precision (family F)**: candidates of family F that exactly match an
  expected item, divided by all candidates emitted in F. A family is DATE
  (DATE, TIME, DATETIME), MONEY, URL, EMAIL or PHONE. A candidate is
  assigned to the family of its own kind, not the row's stress kind, so a
  PHONE row that emits MONEY counts against MONEY.
- **Exact match**: a definite value with the same canonical text, or a
  clarification whose readings equal the expected readings set (same kind
  and raw). The `ambiguity` list is compared as a set.
- **Recall (family F)**: expected items of F matched exactly, divided by
  expected items of F. A clarification is one expected item.
- **Miss categories**: WRONG (matched but not exact), ABSTAINED (a recorded
  abstention with the same kind and raw), SILENT (neither).
- **Outcomes** of an emitted candidate that is not exact: SUBSET or SUPERSET
  (a clarification whose readings are a strict subset or superset of the
  expected ones), WRONG_VALUE (any other difference), UNEXPECTED (no open
  expected item with that kind and raw).
- **Wrong actionable date**: a definite DATE-family candidate that is not
  exact. A clarification whose readings contain the truth is not a wrong value.
- **Ambiguous d/m flagged**: a row with a truth reading. It counts as flagged
  when a DATE clarification with the same raw holds that truth. A definite
  value for the same raw is not flagged.
- **Bare currency inferred**: a MONEY candidate that has a currency, whose
  symbol is one of the multi-code symbols (`$`, `£`, `¥`, `₩`, `kr`; ADR-0008
  §5.4 table), with no three-letter code in the raw text and no currency
  context code.

## Fixture composition (200 rows)

| Stress kind | Rows | Expected: items only | items + abstain | abstain only | nothing |
| --- | --- | --- | --- | --- | --- |
| DATE | 45 | 26 | 0 | 19 | 0 |
| TIME | 27 | 13 | 1 | 12 | 1 |
| DATETIME | 9 | 7 | 2 | 0 | 0 |
| MONEY | 50 | 33 | 0 | 16 | 1 |
| URL | 15 | 10 | 0 | 0 | 5 |
| EMAIL | 15 | 7 | 0 | 0 | 8 |
| PHONE | 15 | 10 | 0 | 0 | 5 |
| NONE | 24 | 0 | 0 | 0 | 24 |
| **Total** | **200** | 106 | 3 | 47 | 44 |

Truth rows (ambiguous d/m, the `truth` column): 11 (D-13 to D-18, D-25 to D-28, DT-05).

Hostile cases cover ambiguous d/m dates, two-digit years (six readings), grouped
amounts, ranges, credits and debits, phone-versus-money, invisible characters
(`\u` escapes) and look-alike dashes.

## Known deviations (fixture expectation, extractor output, reference)

The gate passes. These deviations are counted in the metrics and reported
here. They were not hidden, and the fixture was not changed to match output.

- **D-1, M-20** (`Total $45.00 USD 33.10`, MONEY, hostile). Expected:
  `MONEY¦$45.00¦45.00 $` (currency null) and `MONEY¦USD 33.10¦33.10 USD`.
  Actual: no candidate. The whole span `$45.00 USD 33.10` is abstained. Silent
  misses: 2. Reference: ADR-0008 §5.4, "another amount's own marker is never
  context" (line 521). The two-line form is normative at line 549. The
  single-line form is an extension by the same rule, so this is a known
  deviation and not a precision failure.
- **D-2, T-14** (`1430`, TIME, negative). Expected: abstention `TIME¦1430`.
  Actual: no candidate and no abstention record. Reference: ADR-0008 line 314
  ("a bare `1430`" is abstained). This affects abstention recall only.

Fixture corrections made during GREEN, each checked against ADR-0008 (commit
`67847c2`):
- T-20: `09:30` has a leading zero, so it is one definite reading (no-marker
  rule, ADR-0008 lines 290-292, Q-5). The earlier `21:30` reading was a misread.
- T-27: the abstention `TIME¦11:00 UTC` had been placed in the truth column. It
  is now in the abstain column.
- M-17, M-25, M-29, M-30: the truth column held clarification readings. Truth is
  for d/m rows only, so it is cleared. The clarification items already hold both
  readings.

## Reproduce

`./gradlew :vault:test` (foreground). Then read `vault/build/facts-eval/summary.tsv`
and `vault/build/facts-eval/rows.tsv`.
