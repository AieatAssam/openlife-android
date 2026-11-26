# P3-03 Typed extractors (ADR-0008 `rules-*-v1`)

Branch `plan/P3-03-conservative-interpreters`, based on `main` at 31cc2eb
(P3-01 merged). Date 2026-11-26. Pure Kotlin in
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
| VERIFY/DOCUMENT | this commit | Evidence, this file, C2.md §7.1 versions table |

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
| vault | 193 | 222 (+29 extractor tests) | 0 |
| app | 146 | 146 | 0 |
| eval | 26 | 26 | 0 |

`scripts/plan-check.sh`: steps=82 errors=0. `bash scripts/tests/run.sh`:
51 passed, 0 failed.

Fixtures (hand-written from ADR-0008; synthetic data only):
`vault/src/test/resources/facts/dates.tsv`, `times.tsv`, `money.tsv`,
`url_email_phone.tsv`. Every ADR-0008 normative example is a row,
including the hostile ones (userinfo URLs, dotted quads, card-like
groups, non-ASCII digits, `$` with two codes in context, English-word
codes).

## Timing (bound 50 ms per 10,000-character input, best of runs)

The bound is measured as the test thread's CPU time
(`ThreadMXBean.currentThreadCpuTime`; wall time if unsupported). Wall time
is printed beside it. This is a measurement decision, recorded as a
deviation for the coordinator. In the first full gate run, with wall
time, lint and `:app:test` ran in parallel on this shared 4-core host
(load average 18–52). Every stage slowed by about 2x at once, and
`markers/ALL` 58 ms and `mixed/ALL` 63 ms failed the bound. The same code
measured 22–33 ms alone. The 50 ms bound and the ratio limit of 40 are
unchanged.

Final gate run (best of 5 after 3 warm-ups; CPU ms / wall ms), slowest:

| Input / stages | CPU | Wall |
| --- | --- | --- |
| mixed / ALL | 20.43 | 45.46 |
| markers / ALL | 17.79 | 36.58 |
| markers / TIME | 16.07 | 16.37 |

Every other input and stage was lower. Linearity ratios at 10x: digit-space
9.5, label-run 15.2, at 5.6, groups 7.2, months 7.1.

`workGrowsLinearly` runs five inputs at 10x size and requires a ratio
below 40.

## Deviations and decisions

- The timing bound is measured as thread CPU time (see Timing).
- Residual: `workGrowsLinearly` allows a ratio below 40 for 10x input, so
  a mild superlinear regression (an O(n) scan every 64 characters) passes
  it. One every 16 characters fails it. An O(n) `indexOf` on every claim
  also passed, because the claims in these inputs are sparse.

- The `€2.675` fixture is ambiguous under ADR-0008 §5.2 (one separator
  followed by exactly three digits), so the decimal-string case uses
  `€1,234.675`.
- `Abstention.kind` is nullable: the non-ASCII-digit guard (Z-16) abstains
  before any kind is known.
- ISO 4217 List One was transcribed by hand on 2026-11-26 (176 codes; XXX
  and XTS left out, as they are not currencies a document states).

## Specification questions (the code abstains or follows the literal text)

Each is decided conservatively in `rules-*-v1` and listed for the owner
to settle in an ADR-0008 revision (also in the P3-03 `notes` in
`plan/plan.yaml`):

- 0:30 is abstained (bare 0 without marker)
- 'D Month, YYYY' with a comma is read as no-year and abstained
- year-first reading only when the first group has two digits
- a two-digit year with one remaining valid reading is abstained
- the range prefix rule (ZZ-06) also applied to numeric dates and to 'to'/'and'
- 09:00-17:00 reads -17:00 as an offset and abstains
- two spaces before a zone token are still treated as a following zone
- '1 234,50 €' with ASCII spaces is abstained
- a marker with numbers on both sides abstains both
- '- €5' with a space before the marker is not a sign
- a three-digit fraction where grouping is impossible ('1234,567', apostrophe grouping) gives the decimal reading only
- 'Tel: (020)...' without + gives nothing
- any scheme:// and the opaque list (javascript, vbscript, data, file, intent, ftp, blob, content, about) are abstained and claimed
- currency context reads neighbour text as printed and ignores claims
- the weekday is included in a date's evidence range
- '.' and 'h' time separators are not claimed
- with two codes in one neighbour span the earliest code's offsets are recorded
- 'at' between date and time matched in any case
- em dash accepted in ranges
- a non-zone abstention of the second time in a range does not propagate to the first
- XXX and XTS excluded from List One markers, BGN kept
- an English-word code alone counts only when every reading has fraction digits

