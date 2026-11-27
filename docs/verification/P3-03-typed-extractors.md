# P3-03 Typed extractors (ADR-0008 `rules-*-v1`)

Branch `plan/P3-03-conservative-interpreters`, based on `main` at 31cc2eb
(P3-01 merged), later merged with `main` at f46ea30 (PR #34). Date 2026-11-26. Pure Kotlin in
`vault/src/main/java/org/openlife/vault/facts/extract/`; JVM tests only.
No Android API, database, schema or UI is touched, so this step has no
instrumented test and no device gap.

## Commits

| Phase | Commit | What |
| --- | --- | --- |
| Claim | 0a19698 | `status: in_progress` |
| RED | e4337aa | 29 tests and the four fixture tables; a stub `FactExtractor` that returns nothing |
| GREEN | 29c1661 | The seven scanners over a shared claim mask; vault detekt clean |
| REFACTOR | d1edb6d | The shared tokenizer (`LineScan`, `LineReads`) came out of GREEN while splitting for detekt. REFACTOR changed only the timing tests to measure thread CPU time (see Timing) |
| Coverage fix | 0780f08 | §5.3 symbol rows; `C$` no longer read as `$` |
| VERIFY/DOCUMENT | 8772560 and this commit | Evidence, this file, C2.md §7.1 versions table |

## RED

At e4337aa the stub extractor returned no candidates, so 26 of the 29 tests
failed on assertions (no compile errors). Three tests pass vacuously
against an empty result. Each was proved by a recorded mutation, applied
and reverted, in VERIFY:

| Test | Mutation | Result |
| --- | --- | --- |
| `ExtractorSafetyTest.noRegexResolvingOrFloatingPointApisInExtractors` | add `private val probe = Regex("x")` to `UrlExtractor` | failed: `expected:<[]> but was:<[UrlExtractor.kt: Regex]>` |
| `ExtractorPropertyTest.offsetsAlwaysSliceToRawText` | `startOffset = output.start + 1` in `LineReads.toCandidate` | failed: `ComparisonFailure` (slice differs from `rawText`) |
| `ExtractorPropertyTest.knownPositivesSurviveSurroundingNoise` | same mutation | failed |
| `ExtractorSafetyTest.workGrowsLinearly` (CPU time) | in `LineScan.reach`, an O(n) `indexOf` every 16 characters | failed: `at grew by 50.7 for 10x input` (limit 40) |
| same, weaker | the same every 64 characters | passed (ratios 16–27): a residual, see below |
| overlap invariant in `offsetsAlwaysSliceToRawText` | remove `claim` from `LineReads.emit` | failed: `overlap in <...>: [5..33, 28..30]` |

## Tests

Final gate, 2026-11-26 (`./gradlew detekt lint :app:test :vault:test
assembleDebug :vault:compileDebugAndroidTestKotlin
:app:compileDebugAndroidTestKotlin :eval:test`, private daemon registry,
`--max-workers=2`): BUILD SUCCESSFUL in 9m 51s.

| Module | Before P3-03 | After | Failures |
| --- | --- | --- | --- |
| vault | 193 (computed: 222 − 29) | 222 (+29 extractor tests) | 0 |
| app | 146 | 146 | 0 |
| eval | 26 | 26 | 0 |

`scripts/plan-check.sh`: steps=82 errors=0. `bash scripts/tests/run.sh`:
51 passed, 0 failed (run alone on the final tree).

Fixtures (hand-written from ADR-0008; synthetic data only):
`vault/src/test/resources/facts/dates.tsv`, `times.tsv`, `money.tsv`,
`url_email_phone.tsv`. A script extracted the 271 backticked strings in
ADR-0008 §4–§7 and searched the fixtures and test sources for each. Every
example value is covered, including the hostile ones (userinfo URLs,
dotted quads, card-like groups, non-ASCII digits, `$` with two codes in
context, English-word codes). The 81 strings not found verbatim are
notations (`D Month YYYY`, `±HH:MM`, `T1–T2`), enum or column names,
outputs written in another notation (`3.50 $ \| 3.50 CUP`), or
symbol-table entries. Commit 0780f08 then added rows for every §5.3
symbol and for the non-markers `C$`, `Rs` and `¢`.

## Timing (bound 50 ms per 10,000-character input, best of runs)

The bound is measured as the test thread's CPU time
(`ThreadMXBean.currentThreadCpuTime`; wall time if unsupported). Wall time
is printed beside it. This is a measurement decision, recorded as a
deviation for the coordinator. In the first full gate run, with wall
time, lint and `:app:test` ran in parallel on this shared 4-core host
(load average 18–52). Every stage slowed by about 2x at once, and
`markers/ALL` 58 ms and `mixed/ALL` 63 ms failed the bound. The same code
measured 22–33 ms alone. The 50 ms bound and the ratio limit of 40 are
unchanged at that time; the review fix later lowered the ratio limit to
20 (see Review fixes).

Final gate run (best of 5 after 3 warm-ups; CPU ms / wall ms), slowest:

| Input / stages | CPU | Wall |
| --- | --- | --- |
| mixed / ALL | 20.43 | 45.46 |
| markers / ALL | 17.79 | 36.58 |
| markers / TIME | 16.07 | 16.37 |

Every other input and stage was lower. Linearity ratios at 10x: digit-space
9.5, label-run 15.2, at 5.6, groups 7.2, months 7.1.

`workGrowsLinearly` ran five inputs at 10x size and required a ratio
below 40. Since the review fix it runs seven (adding `time-ann` and
`url-many`) and requires a ratio below 20.

## Deviations and decisions

- The timing bound is measured as thread CPU time (see Timing).
- `C$5` was read as `$5` with currency null. Since 0780f08, a `$` with
  another letter attached before it is not a marker, so nothing is
  emitted (§5.3 excludes `C$`). `rules-money-v1` is unreleased, so the
  version stays `v1`.
- Residual: `workGrowsLinearly` allowed a ratio below 40 for 10x input, so
  a mild superlinear regression (an O(n) scan every 64 characters) passed
  it. One every 16 characters failed it. The review fix lowered the limit
  to 20 (see Review fixes). An O(n) `indexOf` on every claim
  also passed, because the claims in these inputs are sparse.

- The `€2.675` fixture is ambiguous under ADR-0008 §5.2 (one separator
  followed by exactly three digits), so the decimal-string case uses
  `€1,234.675`.
- `Abstention.kind` is nullable: the non-ASCII-digit guard (Z-16) abstains
  before any kind is known.
- ISO 4217 List One was transcribed by hand on 2026-11-26 (176 codes; XXX
  and XTS left out, as they are not currencies a document states).

## Specification questions and rulings

Rulings by the coordinating session, 2026-11-26, after the adversarial
review (ADR-0008 "Amendments 2026-11-26"). "Keep" means the v1 behaviour
below stands; "v2" means deferred to rules v2 (a follow-up in the P3-03
`notes`), with v1 behaviour unchanged.

| Q | Question (v1 behaviour) | Ruling |
| --- | --- | --- |
| 1 | `0:30` is abstained (bare 0 without marker) | keep |
| 2 | `D Month, YYYY` with a comma is read as no-year and abstained | v2 |
| 3 | year-first reading only when the first group has two digits | keep |
| 4 | a two-digit year with one remaining valid reading is abstained | keep; now normative (E-03, A-3), and `Thu 03/04/26` fixtures corrected |
| 5 | the range prefix rule (ZZ-06) also applied to numeric dates and to `to`/`and` | keep |
| 6 | `09:00-17:00` reads `-17:00` as an offset and abstains | v2 |
| 7 | spaces before a zone token | changed (E-08, A-8): at most 4 Unicode space separators or tabs are skipped, so `14:30  CET` (two spaces), `14:30    CET` (four), tab, U+00A0, U+2009 and U+202F gaps abstain; five spaces do not (times.tsv rows 134-140). The earlier entry ("two spaces ... still treated as a zone") was wrong: before the fix the code skipped one space only, so `14:30  CET` gave `14:30` (reviewer corpus baseline) |
| 8 | `1 234,50 €` with ASCII spaces is abstained | keep |
| 9 | a marker with numbers on both sides abstains both | keep |
| 10 | `- €5` with a space before the marker | changed (E-04, A-4): abstained as `SIGN_OR_CREDIT` |
| 11 | a three-digit fraction where grouping is impossible (`1234,567`, apostrophe grouping) | changed (A-10): abstained |
| 12 | `Tel: (020)...` without `+` gives nothing | v2 |
| 13 | any `scheme://` and the opaque list are abstained and claimed | keep |
| 14 | currency context reads neighbour text as printed and ignores claims | keep |
| 15 | the weekday is included in a date's evidence range | keep |
| 16 | `.` and `h` time separators are not claimed | changed (A-11): `2.30 pm` and `14h30` are claimed and abstained; a bare `14.30` is not a time |
| 17 | with two codes in one neighbour span the earliest code's offsets are recorded | keep |
| 18 | `at` between date and time matched in any case | keep |
| 19 | em dash accepted in ranges | keep |
| 20 | a non-zone abstention of the second time in a range does not propagate | changed (A-11): any abstention of either time abstains the whole range |
| 21 | XXX and XTS excluded from List One markers, BGN kept | keep; the list was checked against the host JDK (below) |
| 22 | an English-word code alone counts only when every reading has fraction digits | keep |
| 23 | `C$` is not a marker at all, so `C$5` gives nothing | keep |

## Review fixes (2026-11-26)

The adversarial review (E-01 to E-10) was answered after a rename
(`FactValue` to `TypedValue`, 1278528): RED fixtures and tests (ec446c9),
then the fix (03ad028), then these documents. Each finding and its
ADR-0008 amendment:

| Finding | Amendment | Fixed behaviour (fixture examples) |
| --- | --- | --- |
| E-01 | A-1 | `€5 000` gives `5 \| 5000 EUR`; `1 000 €` gave `0 \| 1000 EUR`, since EE-06 `1 \| 1000 EUR` |
| E-02 | A-2 | `14:30:00.000Z`, `UTC+05:30`, `14:30 GMT+01:00` abstain or give nothing |
| E-03 | A-3 | `Thu 03/04/26` abstains (was a single date) |
| E-04 | A-4 | `−€5.00`, `- €5`, `( €5.00 )`, `EUR -33.10` abstain `SIGN_OR_CREDIT`; `USD -33.10` on the next span does not resolve `$5` (money.tsv row 184) |
| E-05 | A-5 | `2026-09-16-17` abstains; `Ref 123-2026-09-16` gives nothing |
| E-06 | A-6 | a zero-width or combining mark inside or beside a token abstains it |
| E-07 | A-7 | `€10–€20`, `€10 to €20`, `between €10 and €20` abstain as one range |
| E-08 | A-8 | tab, U+2009, U+202F, U+00A0 gaps behave like spaces, up to 4 |
| E-09 | A-9 | `+1 555 123 4567 USD` was a phone (since EE-01 it abstains); `+12.345.678` and `+1234567.89` abstain; `1.2.33` stays a date candidate (residual) |
| E-10 | A-10 | `xn--` and `-`-edged email labels rejected; `_` after a domain abstains; `€0.500` abstains; `2:30 PM` reads its marker; `RULES_VERSION` asserted equal to the joined rule constants |

Two RED fixtures were corrected during GREEN: `14:30 GMT+1` now abstains
over the whole `14:30 GMT+1` (a zone word takes its offset), and the tab
row was rewritten as `\u0009` because the fixture reader decodes only
`\uXXXX`. The noise property test now separates noise from the text with
`;`, because a dash next to an amount abstains it under A-4; without that,
950 of 1,000 noisy positives survived.

`MoneyTables.LIST_ONE` was compared with `java.util.Currency` of the host
JDK (OpenJDK 21.0.13.1): 176 codes here; every one is known to the JDK
except UYW; the JDK's other codes are withdrawn ones, XXX, XTS and XAD.
BGN is in both. No ISO edition is cited, because the edition transcribed
was not recorded.

The property alphabets gained the E-01 to E-10 input classes (leading
zeros, `.000`, U+2212, em dash, `±`, U+200B, U+200D, U+0301, U+0305, tab,
U+2009, U+202F, U+00A0, `GMT`, `UTC`, `h`, `_`, `xn--`). The linearity
inputs gained `time-ann` and `url-many`, and the ratio limit fell from 40
to 20. With that limit, the O(n) scan every 64 characters that passed
before now fails (`digit-space grew by 21.0`, kotlinc run, mutation
reverted); the margin is small, so the residual is narrowed, not closed.

Reviewer corpus re-run (995 output lines): every E-01 to E-10 case changed
as ruled. Remaining outputs that a reader could question, all stated in
ADR-0008 as v1 residuals: `14:30 +1` and `14:30 -5` give `14:30`;
`14:30 Zulu` gives `14:30`; `EUR/USD 1.08` gives `1.08 USD`; `€5.00 Cr`
(lowercase) gives `5.00 EUR`; `Coffee – €3.50` gives nothing.

Gate after the fix, 2026-11-26 (same command and settings as above):
BUILD SUCCESSFUL in 10m 32s. vault 224 tests (31 extractor tests; 2 new:
`rulesVersionJoinsEveryRuleConstant`, `duplicateSpanOrdinalsAreRejected`),
app 189 (main's own growth since the first gate), eval 26; 0 failures,
0 skipped. Timing, slowest (CPU ms / wall ms): time-ann / ALL 15.10 /
16.88, times / ALL 14.55 / 14.98, mixed / ALL 12.66 / 17.58. Linearity at
10x: digit-space 8.1, label-run 10.6, at 8.4, groups 6.9, months 7.2,
time-ann 9.8, url-many 11.3 (limit 20). `scripts/plan-check.sh`: steps=82
errors=0. `bash scripts/tests/run.sh`, run alone: 51 passed, 0 failed.

## Verification fixes (2026-11-26, EE-01 to EE-06)

The coordinator's verification pass found E-01 to E-05, E-08 and E-10
resolved and six findings still open. RED fixtures (4c2ccab: kotlinc 34
tests, 6 failing), then the fix (d708095), then these documents.

| Finding | Amendment | Fixed behaviour (fixture examples) |
| --- | --- | --- |
| EE-01 | A-9 (a) | a `+`-led run of 7–15 digits with a free currency marker within one space separator either side abstains PHONE `AMBIGUOUS_FORM`: `+1234567 EUR`, `+1234567 €`, `€ +1234567`, `Balance: +1234567 GBP`, `Gain +2500000 JPY`, `+1 234 567 EUR`, `+1 555 123 4567 USD`. `+123456 EUR` and `+5 €` stay money; `Call +44 20 7946 0018 €5` keeps both |
| EE-02 | A-12 | a space-grouped amount ending in a fraction abstains whole, prefix and suffix: `€5 000.00`/`5 000.00 €`, `$1 234.56`, `CHF 1 234.50`, `€12 345,67`, `€1 000 000.50`, U+2009 and tab variants |
| EE-03 | A-7 | `10 and 20 €`, `between 10 and 20 €`, `-€5 to -€10`, `€-5 - €-10`, `€10 and €20 and €30`, `€10-€20-€30` abstain as one range; `€10 and 20` stays `10 EUR` |
| EE-04 | A-6 | `\u200E2026-09-16`, `\u200F14:30`, `€\u200D5`, `2026\u200B-09-16` abstain over the mark (no kind when no abstention is touched); the ZWSP email abstention now starts at the mark. Emoji ZWJ sequences beside an amount are left alone |
| EE-05 | A-4 | `--€5`, `±€5`, `−-€5` abstain `SIGN_OR_CREDIT` |
| EE-06 | A-12 | `€5 000` `5 \| 5000`; `1 000 €` `1 \| 1000`; `12 005 €` `12 \| 12005`; `1 000 000 €` `1 \| 1000000`; `€0 001`, `0 500 €`, `€ 001 000` abstain |
| rulings | A-13 | `14:30 Zulu`/`zulu` abstain; `EUR/USD 1.08`, `1.08 EUR/USD`, `EUR / USD 1.08` abstain and a pair is not context (`$5` then `EUR/USD` gives `5 $`); `€5.00 Cr`, `dr`, U+00A0 `CR`, tab `Dr` abstain; `€5.00 Credit` stays `5.00 EUR` |

Fixture corrections during GREEN: the existing `12 005 €` row took the
new first-group reading (its duplicate was dropped), the old
`u\u200Bser@example.com` row's expectation was replaced by the wider
abstention, and the noise property test no longer expects a mark beside a
fenced money positive (the fence `;` is what touches the mark).

Tests added: `knownPositivesSurviveSurroundingNoise` now fences only the
money positive and lets the other kinds touch space separators, marks and
dashes; `spaceSeparatorsBesideTokensStillRead` asserts NBSP, tab, U+2009
and U+202F beside each kind still read; `everyExtractedKindIsAFactKind`
compares the enum names with `org.openlife.vault.facts.FactKind`
(mutation: adding an `ADDRESS` entry fails it); `scannerReadsGrowLinearly`
counts `LineScan.ch`/`raw` reads, which are exactly linear today (for
example digit-space 209,999 to 2,099,999 at 10x), and the linearity
inputs gained `range-chain` and `mark-led`.

The work counter is deterministic but sees only reads through `LineScan`.
A mutation that loops through `ch()` every 64 characters fails it (and
the timing test). The original mutation, an O(n) `text.indexOf` every 64
characters, bypasses the counter; only the timing test sees it, at ratio
21.0 and 41.8 in two kotlinc runs against the limit of 20. **The every-64
`indexOf` mutation is therefore not reliably caught**: the first run
passed the limit by 1.0.

Reviewer corpus re-run (`cases.txt` to `cases7.txt`, compared with the
previous outputs): 67 inputs changed, and none gained a definite value
(checked by script: no new output line without `|`). Classified:
- now abstained, previously a definite value: EE-01 (`+1234567 EUR` and
  its variants, `+44 20 7946 0018 USD` and `+1 555 123 4567 USD` phones
  are now recall losses), EE-02 prefix fractions (were truncated to the
  first group), EE-03 ranges (were the last amount), EE-05 signs,
  `EUR/USD 1.08`, `€5.00 Cr`/`cr`, `14:30 Zulu`, `+1,234,567 USD` and
  `Balance +1234567.89 EUR` (were money, now PHONE abstentions);
- abstention widened only (same outcome): `GBP/USD 1.2345`, the ZWSP
  email, `-−€5`, `€10–€20–€30` style chains;
- newly abstained where nothing was emitted: leading marks
  (`\u200E2026-09-16`, `\u202E…`, `\u200F14:30`, `€\u200D5`, `€\u03015`,
  `2026\u200B-09-16`, `5\u20E3 €`);
- clarification sets changed (still clarifications): `1 000 €`,
  `1 000 000 €`, `100 000 €` (`0` became the first group), `12 005 €`
  (`5` became `12`);
- clarification now abstained: `€0 001`, `€ 001 000`, `0 000 €`,
  `00 000 €`.

Residuals kept: `14:30 +1` gives `14:30`; `Coffee – €3.50` gives nothing;
`9:00 Eastern` reads `9:00`.

Gate on d708095, 2026-11-27 (`./gradlew detekt lint :app:test :vault:test
assembleDebug :vault:compileDebugAndroidTestKotlin
:app:compileDebugAndroidTestKotlin :eval:test`, private daemon registry,
`--max-workers=2`): BUILD SUCCESSFUL, 40m 14s wall on the shared host.
`scripts/plan-check.sh`: steps=82 errors=0. `bash scripts/tests/run.sh`,
run alone: 51 passed, 0 failed.
vault 265 tests, app 211, eval 26; 0 failures, 0 errors, 0 skipped.
Timing, slowest (CPU ms / wall ms): time-ann / ALL 16.68 / 38.76,
plus / ALL 12.65 / 13.50, markers / ALL 10.12 / 14.84. Linearity at 10x:
digit-space 9.0, label-run 7.6, at 7.8, groups 8.5, months 10.6, time-ann
11.2, url-many 14.4, range-chain 10.4, mark-led 8.3 (limit 20). The
documentation commit after it changes only Markdown and `plan/plan.yaml`.

