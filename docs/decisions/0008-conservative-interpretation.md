# ADR-0008: Conservative interpretation of typed facts

Date: 2026-11-25
Status: proposed for P3-01; pending adversarial review and owner approval
(OA-4). Normative for C2 (`docs/capabilities/C2.md`) once approved.

## Context

C2 turns OCR text into dates, times, amounts, web addresses, email
addresses and phone numbers (design §5). Design §4 fixes the attitude:

- an ambiguous date such as 03/04 needs clarification;
- a date-only value stays a date, not an invented midnight instant;
- no currency is inferred from a symbol without enough context;
- amounts are decimal with a currency code, never binary floating point;
- missing information stays missing.

Design §14 adds that a wrong actionable date is worse than a missed one.
P3-01-R2 fixes the outline of each encoding. P3-03-R1 fixes the extractor
families. What remained open:

- the exact bytes of each value;
- what counts as ambiguous;
- which symbols map to a currency;
- how far normalisation may go;
- what happens to zones, two-digit years, 12-hour times and digit
  separators.

The inputs are line-level OCR spans in Latin script, untrusted, often
noisy, with no calibrated confidence (C1). The extractors are rules, not a
model, and run on the device with no dependency (C2-R2). Their outputs
feed C3's event dates, so every interpretation must be one a user can
check against the line in two taps.

## Options considered

### A. How to resolve ambiguity (d/m vs m/d, digit separators, 12-hour times)

1. **Resolve with the device locale.** Short and familiar, but it is a
   guess. The locale of the phone says nothing about the locale of a
   letter from abroad or a screenshot of a foreign booking. A wrong date
   produced silently is the failure design §14 ranks worst. Rejected.
2. **Resolve with heuristics from the document** (other dates in the same
   item, words like "Rechnung", a currency). Better on average and still a
   guess. It is also untestable against the evidence: the user sees a
   chosen value and cannot tell why. Rejected for C2. Rule versions can
   revisit it with P3-06 data, as an ADR.
3. **Abstain on every ambiguity.** Safe, but it loses most numeric dates
   in many regions.
4. **Emit a bounded candidate set, status `CLARIFICATION_NEEDED`, and let
   the user choose.** **Chosen.** It costs one tap. It never stores a
   guessed value as established, and the chooser shows exactly what the
   text allows.

### B. Money amounts

1. **Binary floating point** (`Double`). Forbidden by design §4.
2. **Minor units as an integer** (`Long` cents). Exact. But it needs the
   currency's exponent, which is unknown when the currency is null, and it
   loses the printed scale (`42.5` vs `42.50`). It also breaks on
   three-decimal currencies unless every case is special-cased. Rejected.
3. **Decimal string with the printed scale** (`"42.50"`), parsed with
   `BigDecimal` where arithmetic is ever needed. **Chosen.** It is exact,
   currency-independent, byte-comparable, and faithful to the evidence.

### C. Currency from a symbol

1. **Map every symbol to its most common code** (`$`→USD, `£`→GBP,
   `¥`→JPY). Wrong for every Canadian, Australian, Egyptian or Chinese
   receipt. Rejected.
2. **Map with the device locale.** A guess, as in A1. Rejected.
3. **Map only a symbol used by exactly one ISO 4217 code, and accept an
   explicit code in the same or an adjacent line.** **Chosen**, as
   P3-03-R1 states. A multi-code symbol with no code yields
   `currency: null`, and `rawSymbol` keeps what was printed.

### D. Phone numbers

1. **libphonenumber.** Accurate, but it is a new runtime dependency
   (C2-R2 forbids one), and its strength is inferring the region and
   country code, which is the inference this ADR refuses. Rejected.
2. **Hand-written digit-count rule (7–15 digits, optional `+`), with no
   country inference.** **Chosen** (P3-03-R1). The normalised value keeps
   only what was printed.

### E. URLs

1. **`java.net.URL`, `URI` or `android.util.Patterns.WEB_URL`.** Rejected:
   - `URL` may resolve the host and has handler side effects;
   - `URI` normalisation silently rewrites;
   - `Patterns` is an Android dependency inside a pure package, and
     backtracking-prone on adversarial input (P3-03-R3/R4).
2. **Hand-written scanner with a narrow grammar.** **Chosen.** It
   recognises `http`, `https` and `www.` only. It lowercases only the
   scheme and host, and never decodes or resolves anything.

### F. Zones and instants

1. **Attach the device zone to every date-time.** This invents a fact.
   Rejected.
2. **Parse explicit zone designators** (`Z`, `+01:00`, `CET`):
   - abbreviations are ambiguous (`CST`, `IST`);
   - offsets are exact but rare on the inputs C2 targets;
   - C3 (ADR-0009, P4-01) owns zone selection for reminders, with the
     user choosing.

   Deferred.
3. **C2 stores local (floating) dates and times only, and abstains on a
   date-time that carries a zone designator.** **Chosen.** The raw text
   stays visible in the reading, and the user can add the fact by hand.

## Decision

### §1 General

- Every extractor works on the read text of one eligible span (C2-R1). A
  candidate never spans two lines. The one exception is the
  `CURRENCY_CODE` evidence of §5.4.
- Token boundaries: a match must not be preceded or followed by a letter
  or a digit (Unicode `Character.isLetterOrDigit`). This stops matches
  inside longer tokens (`12/04/20261`, `ab12:30`).
- Overlap precedence is `URL > EMAIL > DATETIME > DATE > TIME > MONEY >
  PHONE` (C2-R28).
- Matching is linear-time scanning or non-backtracking patterns. Each rule
  completes on 10,000 adversarial characters in under 50 ms (P3-03-R3).
- Rule identifiers are `rules-date`, `rules-time`, `rules-datetime`,
  `rules-money`, `rules-url`, `rules-email`, `rules-phone`, all at
  version `v1`. The reference data is part of the rule version: the ISO
  4217 list, the symbol table and the English month names. Changing any
  of it bumps the version.
- `locale` is `en` when an English month or weekday name was matched, and
  `und` otherwise.

### §2 Canonical JSON (`valueJson`)

- UTF-8 JSON objects with keys in the order given below, no whitespace,
  strings escaped only where JSON requires (`"`, `\`, control
  characters), and no number type anywhere: every scalar is a string or
  `null`.
- Equal values are equal bytes. A writer serialises from the typed value;
  it never re-serialises parsed user JSON.

Shapes by kind:

- `DATE`: `{"date":"YYYY-MM-DD"}`. A proleptic Gregorian date with a year
  from 1000 to 9999.
- `TIME`: `{"time":"HH:mm"}`, or `{"time":"HH:mm:ss"}` when seconds were
  printed. `HH` runs from 00 to 23.
- `DATETIME`: `{"date":…,"time":…}`.
- `MONEY`: `{"amount":"…","currency":"XXX"|null,"rawSymbol":"…"|null}`.
  - `amount`: an optional `-`, then the integer digits with no leading
    zero (except `0`), then optionally `.` and the fraction digits exactly
    as printed.
  - `currency`: uppercase.
  - `rawSymbol`: the symbol characters as printed, for example `€`, `$`,
    `US$` or `kr`. It is `null` when only a code was printed.
- `URL`, `EMAIL`, `PHONE`: `{"normalized":"…","raw":"…"}`. `raw` is
  exactly the evidence slice.
- Candidates: `{"candidates":[v1,v2,…]}`, with 2 to 64 values of the
  revision's kind, ordered as follows:
  - dates, times and date-times chronologically;
  - money by numeric value, then lexicographically by `amount`.

  Duplicates are removed. A set that reduces to one value is not ambiguous
  and is stored as that value.

### §3 `canonicalText`

This is the search and comparison text. It never depends on locale.

| Kind | `canonicalText` |
| --- | --- |
| `DATE` | `YYYY-MM-DD` |
| `TIME` | `HH:mm[:ss]` |
| `DATETIME` | `YYYY-MM-DD HH:mm[:ss]` |
| `MONEY` | `amount` + space + (`currency` ?: `rawSymbol` ?: empty, trimmed) |
| `URL`, `EMAIL`, `PHONE` | `normalized` |
| candidates | each candidate's text, joined by ` \| ` |

### §4 Dates and times

#### §4.1 Dates (`rules-date-v1`)

- **ISO and year-first forms.** `YYYY-MM-DD`, `YYYY/MM/DD` and
  `YYYY.MM.DD`, with one or two digits for month and day. These are
  unambiguous.
- **Numeric day-month-year forms**: `A/B/YYYY`, with separators `/`, `-`
  or `.`, and `A` and `B` of one or two digits.
  - Reading 1 is day `A`, month `B`. Reading 2 is month `A`, day `B`.
  - If both readings are valid and differ, the result is a candidate pair
    with status `CLARIFICATION_NEEDED`. For example, `03/04/2026` gives
    `2026-03-04` and `2026-04-03`.
  - If exactly one reading is valid, that reading is the value. For
    example, `13/04/2026` gives `2026-04-13`.
  - If neither reading is valid, nothing is emitted. For example,
    `31/04/2026` emits nothing.
  - `04/04/2026` is a single value.
- **Two-digit years**: `A/B/YY`. These are always ambiguous. The
  candidates are each valid day-month reading combined with both `19YY`
  and `20YY`, at most 4 in all. They are never a single value, even when
  the day and month are unambiguous (see C2 Q-4).
- **Month names in English.** Full names and three-letter abbreviations,
  plus `Sept`, in any letter case, with an optional trailing `.`.
  - Accepted forms are `D Month YYYY`, `Month D, YYYY`, `Month D YYYY` and
    `D-Mon-YYYY`.
  - Ordinal suffixes `st`, `nd`, `rd` and `th` are accepted.
  - These forms are unambiguous.
- **Weekday names.** An English weekday name before the date (`Tue`,
  `Tuesday`, with or without a comma) is checked against the date. A
  mismatch emits nothing: it signals an OCR or source error. A weekday
  alone emits nothing.
- **Abstained (no fact):**
  - no year (`3 April`, `03/04`);
  - relative dates (`tomorrow`, `next Tuesday`, `in 30 days`);
  - ranges (`3–5 April 2026`);
  - year-month only;
  - Roman-numeral months;
  - non-English month names.

#### §4.2 Times (`rules-time-v1`)

- **24-hour form.** `HH:mm` or `HH:mm:ss`, with `:` as the separator.
  - If the hour is 13 to 23, or written with a leading zero (`00` to `09`),
    the value is unambiguous. `00:xx` is accepted.
- **12-hour form.** `h[:mm[:ss]]` followed by `am`, `pm`, `a.m.` or `p.m.`,
  in any letter case, with optional space.
  - Hours 1 to 11 are unambiguous.
  - Hour 12 is ambiguous. The candidates are `00:mm` and `12:mm`, because
    `12 am` and `12 pm` are commonly misused.
- **No marker.** An hour from 1 to 12, without a leading zero and without
  a marker, as in `2:30`, `10:30` or `12:15`, is ambiguous. The candidates
  are `h:mm` and `h+12:mm`. For hour 12 they are `00:mm` and `12:mm`.
- **Ranges.** `T1–T2` and `T1 - T2` produce two separate TIME facts
  (P3-03-R1). Each is interpreted on its own, except that an am or pm
  marker printed only after `T2` also applies to `T1` when `T1` has no
  marker.
- **Abstained:**
  - `24:00`;
  - a minute or second over 59;
  - `.` or `h` as the separator (`14.30` and `14h30` collide with
    amounts);
  - bare `1430`;
  - any time followed by a zone designator (§4.3).

#### §4.3 Date-times and zones (`rules-datetime-v1`)

- **When a DATETIME is emitted.** Only when a date match and a time match
  lie in the same span and nothing separates them except whitespace, `,`,
  `at`, `@`, `T` or `-`, in either order, and **both are unambiguous**.
  The parts are then not emitted separately.
- **When parts are ambiguous.** If either part is ambiguous, the date and
  the time are emitted as separate facts with their own candidates. No
  cross-product of DATETIME candidates is formed.
- **Zone designators.** These are `Z`, `±HH:MM`, `±HHMM`, `UTC`, `GMT`,
  `UTC±…`, or a run of 2 to 5 capital letters immediately after the time.
  A date-time or time followed by one emits nothing for that date-time or
  time; the date alone is still emitted when it is a separate token. A
  zone is never stored, and the device zone is never attached. The
  `timezone` column stays NULL in C2.

### §5 Money (`rules-money-v1`)

#### §5.1 What is money

A number is money only when a currency marker sits directly before or
after it, separated by at most one space:

- a symbol from §5.3; or
- an ISO 4217 code from the pinned active list, in uppercase.

A bare number is never money. A minus sign immediately before the marker
or the number makes the amount negative. Parentheses, `CR` and `DR` are
not interpreted; the candidate is abstained.

#### §5.2 Digits and separators

The separators are `,`, `.`, space, narrow no-break space and `'`.

- **Both `,` and `.` present.** The last one to appear is the decimal
  separator, and the other must form valid groups of 3. Examples:
  `1,234.56` and `1.234,56` both read as `1234.56`.
- **One separator kind, used more than once.** It is grouping, and each
  group must have 3 digits. Example: `1,234,567`.
- **One separator, followed by 1 or 2 digits.** It is the decimal
  separator. Examples: `42.50`, `42,5`. Followed by 4 or more digits, the
  candidate is abstained (it exceeds the fraction limit below).
- **One `,` or `.` followed by exactly 3 digits.** This is ambiguous. The
  two candidates are the decimal reading (`1.234`) and the grouping reading
  (`1234`).
- **Space, narrow no-break space and `'`.** These are grouping only.
- **Limits.** At most 15 integer digits and at most 3 fraction digits;
  anything longer is abstained. A single `.` or `,` followed by exactly 3
  digits is treated as above, never as 3 decimals.

#### §5.3 Symbol table (v1)

Single-code symbols map to a currency:

| Symbol | Currency |
| --- | --- |
| `€` | EUR |
| `₹` | INR |
| `₺` | TRY |
| `₽` | RUB |
| `₪` | ILS |
| `₴` | UAH |
| `₫` | VND |
| `₦` | NGN |
| `₸` | KZT |
| `₼` | AZN |
| `₾` | GEL |
| `US$` | USD |
| `CA$` | CAD |
| `AU$` | AUD |
| `NZ$` | NZD |
| `HK$` | HKD |
| `S$` | SGD |
| `R$` | BRL |

Multi-code symbols give `currency: null` unless §5.4 applies:

| Symbol | Candidate codes |
| --- | --- |
| `$` | USD, CAD, AUD, NZD, HKD, SGD, MXN, ARS, CLP, COP, TWD, JMD, TTD, BSD, BBD, BZD, BMD, BND, FJD, GYD, KYD, LRD, NAD, SBD, SRD, XCD, DOP, CUP |
| `£` | GBP, EGP, FKP, GIP, LBP, SHP, SSP, SYP |
| `¥` | JPY, CNY |
| `₩` | KRW, KPW |
| `kr` | DKK, ISK, NOK, SEK |

Notes on the table:

- `C$` is excluded, because it is used for both CAD and NIO.
- `Fr`, `R`, `Rs`, `¢` and `L` are not recognised as markers in v1.

#### §5.4 Code in the same or an adjacent line

When the marker is a multi-code symbol, the extractor looks for ISO codes
in:

- the same span;
- the eligible spans with ordinal ±1 in the same reading.

If exactly one distinct code is found and it is among the symbol's
candidate codes, `currency` is that code. When the code is in an adjacent
span, the revision gets a second evidence row with role `CURRENCY_CODE`.
In every other case `currency` is `null`: no code, two different codes, or
a code outside the symbol's candidates.

A code printed directly beside the number is a marker under §5.1, not
context. If it conflicts with a symbol on the same amount (`$ 10 EUR`),
`currency` is `null`.

### §6 URLs and email addresses

#### §6.1 URLs (`rules-url-v1`)

- **Start.** `http://` or `https://`, in any letter case, or `www.` at a
  token start.
- **Host.** ASCII labels of `[A-Za-z0-9-]`, separated by `.`, with at
  least two labels. An optional `:port` of 1 to 5 digits may follow.
- **Rest.** Path, query and fragment use the RFC 3986 unreserved and
  reserved characters and `%`.
- **End.** The URL ends at whitespace or at any other character. Trailing
  `.,;:!?'"` characters are trimmed, as is a closing `)]}` that has no
  opening partner inside the URL.
- **Abstained:**
  - userinfo (`user@host`, including `https://bank.example@evil.example`);
  - a non-ASCII or punycode-looking host (`xn--`);
  - other schemes (`javascript:`, `data:`, `file:`, `intent:`, `ftp:`
    and others; `mailto:` is handled by §6.2, `tel:` by §7);
  - longer than 2,048 characters.
- **Normalisation.** The scheme and host are lowercased; nothing else
  changes. No scheme is added to a `www.` URL. There is no
  percent-decoding, no default-port removal, no trailing-slash change, and
  no resolution.

#### §6.2 Email addresses (`rules-email-v1`)

- **Local part.** A dot-atom of `[A-Za-z0-9._%+-]`, 1 to 64 characters,
  with no leading, trailing or doubled dot. Quoted local parts are
  abstained.
- **Domain.** ASCII labels as for URLs, at least two, with a final label
  of 2 or more letters.
- **Total length.** At most 254 characters.
- **`mailto:`.** A `mailto:` prefix is consumed and is not part of the
  value.
- **Normalisation.** The local part is unchanged, because it is
  case-sensitive by RFC 5321. The domain is lowercased.

### §7 Phone numbers (`rules-phone-v1`)

- **Accepted form.** An optional `tel:` prefix, an optional leading `+`,
  then digits separated by single spaces, `-`, `.`, or a parenthesised
  group such as `(0)20` or `(555)`. The run must contain 7 to 15 digits in
  all.
- **The run must carry a `+` or at least one separator.** A pure digit run
  is abstained, because it is too often an order or account number.
- **Abstained:**
  - any run containing `/` or `:`;
  - any run already claimed by a higher-precedence kind;
  - more than one `+`, or a `+` that is not first.
- **Normalisation.** The `+` is kept if printed, followed by all digits.
  A `(0)` trunk digit is dropped only when the number starts with `+`.
  No country code is added, and no trunk prefix is otherwise removed.

## Consequences

- **User effort.** More values need one tap of clarification: numeric
  dates where both readings are valid, two-digit years, times without a
  marker, and amounts with a single three-digit group. In exchange, no
  extracted value is a silent guess. P3-06 measures how often
  clarification is needed. A change to any rule here is a version bump
  recorded per version, never a silent loosening; thresholds change only
  by ADR (P3-06).
- **Recall.** Recall is lower than a locale-guessing extractor's:
  - dates without a year are abstained;
  - phones without separators are abstained;
  - non-English month names are abstained;
  - bare numbers are never money;
  - zoned times are abstained.

  The design accepts this trade: date precision is gated at ≥ 0.97 and
  recall at ≥ 0.85.
- **No dependencies.** No new runtime dependency, permission or Android
  API is needed. The reference tables (ISO 4217 list, symbols, month
  names) live in source as versioned constants.
- **Inert values.** URL, email and phone facts are text the user can read.
  Their normalised forms are never handed to any API that could resolve
  or open them.
- **C3 obligations.** C3 inherits floating local dates and times and must
  choose a zone explicitly (ADR-0009). C4 inherits `currency: null` and
  must have the user confirm a currency before a total is confirmed
  (P6-01).

## Flagged for the adversarial review

- `12 am/pm`, and times without a marker, need clarification (D-9, C2
  Q-5).
- Both centuries are candidates for two-digit years (C2 Q-4).
- A single `,` or `.` followed by three digits is ambiguous, even in
  English text.
- `£` is not mapped to GBP.
- Pure digit runs are not phones.
- URLs with userinfo, and non-ASCII hosts, are abstained rather than
  shown.
- A date-time with a zone designator is abstained entirely.
