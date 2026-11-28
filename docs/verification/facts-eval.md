# Facts evaluation: metrics (eval/facts/v1, JVM text part and image part)

Step P3-06. The text part is measured by `FactsEvalTest.gateThresholdsAreMet` on `:vault:test`
against `eval/facts/v1/text.tsv` (200 synthetic text cases). Rules measured:
`rules-date-v1, rules-time-v1, rules-datetime-v1, rules-money-v1, rules-url-v1,
rules-email-v1, rules-phone-v1` (`FactExtractor.RULES_VERSION`). Source of the
numbers: `vault/build/facts-eval/summary.tsv`, written by the test run (2026-11-28).
The image part (30 cases through OCR, on the tablet) is in the section below.

Scope and gaps:
- The 30 image cases (R1) are measured through OCR in the section below (decision Q-12).
  `docs/verification/ocr-eval.md` records the OCR gate as NO_MEASUREMENT.
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

Gate status: met on the JVM text part. The image part (below) is measured and
FAILS R2 on both engines: recorded as measured, not tuned. C2 acceptance is not claimed.

## Image part (P3-06-R1): 30 rendered images through OCR, on the tablet

Source: `eval/facts/v1/images` (rendered from `image-cases.tsv` and `text.tsv` by
`./gradlew :eval:generateFactsImages`; `manifest.tsv` lists the SHA-256 of each
file). Test: `FactsImageEval.measureBothEnginesOverTheFactsImages` (androidTest,
`@Slow`, opt-in `p306FactsImage`). Device: HUAWEI AGS2-W09, Android 8.0.0 (sdk 26),
arm64-v8a, adb serial `U8BBB20806201256`, 2026-11-28. Each image goes through the
production OCR engine, then `FactExtractor.extract` over its OCR spans, and is
scored with the text part's rules. The test passed (1 test, 0 failures, about
26.5 s for both engines). It fails only for an invalid set or an unprocessed image;
a gate miss is recorded, not asserted. Raw reports: `docs/verification/facts-image-results/`.

Engines: Tesseract `tesseract-eng-fast` (`t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd`)
and ML Kit `mlkit-latin` (16.0.1).

| Kind (family) | Tesseract precision | Tesseract recall | ML Kit precision | ML Kit recall | R2 threshold |
| --- | --- | --- | --- | --- | --- |
| DATE (DATE, TIME, DATETIME) | 28/29 = 0.9655 | 28/29 = 0.9655 | 26/27 = 0.9630 | 26/29 = 0.8966 | precision >= 0.97: FAIL both; recall >= 0.85: PASS both |
| MONEY | 13/13 = 1.0000 | 13/13 = 1.0000 | 12/13 = 0.9231 | 12/13 = 0.9231 | precision >= 0.95: Tesseract PASS, ML Kit FAIL |
| URL | 4/4 = 1.0000 | 4/4 = 1.0000 | 3/4 = 0.7500 | 3/4 = 0.7500 | precision >= 0.95: Tesseract PASS, ML Kit FAIL |
| EMAIL | 5/5 = 1.0000 | 5/5 = 1.0000 | 5/5 = 1.0000 | 5/5 = 1.0000 | precision >= 0.95: PASS both |
| PHONE | 4/5 = 0.8000 | 4/5 = 0.8000 | 5/5 = 1.0000 | 5/5 = 1.0000 | precision >= 0.95: Tesseract FAIL, ML Kit PASS |
| Ambiguous d/m flagged | 6/6 | | 6/6 | | = 1.0: PASS both |
| Currency inferred from a bare symbol | 0 | | 0 | | = 0: PASS both |
| Wrong definite values | 2 (DATE 1, PHONE 1) | | 2 (URL 1, MONEY 1) | | reported, not gated |
| **Gate** | **FAIL** | | **FAIL** | | |

Wrong definite values, as the scorer counts them:
- Tesseract DATE family (img-16, `9:15 am` read `9:15am`): the canonical 09:15 is
  correct, but the raw text differs because a space was dropped, so the scorer
  counts the emit as not exact. The same effect gives the Tesseract PHONE count
  (img-20, `+1 555 123 4567` read `+1555 123 4567`, canonical +15551234567 correct).
- ML Kit URL (img-14): an inserted space before `#top` truncates the URL to
  `https://example.org/a?b=1&c=2`. A wrong value.
- ML Kit MONEY (img-30, negative row, `Coffee – €3.50`): the en dash is read as a
  hyphen, giving `-€3.50` (-3.50 EUR). A wrong value.
- ML Kit DATE precision (img-19, `1:30 pm` read `1:30 pmn`): an unexpected
  clarification `01:30 | 13:30` is one of the 27 DATE emits. It is not a definite
  value, so it is not in the wrong-definite count, but it lowers DATE precision.

Silent misses and their cause (the classifier reports both the surface form and
the whitespace-insensitive form):

| Engine | Silent misses | Surface-form cause | Whitespace-insensitive cause |
| --- | --- | --- | --- |
| Tesseract | 2: img-16 `9:15 am`, img-20 phone | OCR_MISREAD 2 | EXTRACTOR_MISS 2 |
| ML Kit | 5: img-14 URL, img-17 `¥500`, img-19 `1:30 pm`, img-21 `6:00 pm` and `11:00 pm` | OCR_MISREAD 2 (img-14, img-17); EXTRACTOR_MISS 3 (img-19, img-21 x2) | EXTRACTOR_MISS 4; OCR_MISREAD 1 (img-17) |

- OCR misreads: Tesseract drops a space in two cases, so its misses are OCR-side. ML Kit
  inserts a space in img-14 and reads `¥500` as `500` in img-17.
- Extractor misses: img-21 (ML Kit) `Doors 6:00 pm -11:00 pm` gives no candidate and
  no abstention for either time. This is a real extractor gap. img-19 is borderline:
  the OCR text `1:30 pmn` is not clean, and the classifier calls it EXTRACTOR_MISS.
- The first tablet run classified whitespace-insensitively only. That hid the
  Tesseract space drops (reported as EXTRACTOR_MISS 2) and gave ML Kit 4 extractor
  and 1 OCR. The metrics were the same. The classifier now records both causes (`3f76a56`).
- Abstention and evidence-location correctness are not reported for the image part.
  Gap (R1).

Findings (follow-ups, not fixed here): recorded as F1 to F7 in `plan/steps/P3-06.yaml`
notes, with the ADR-0008 references to check.

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
