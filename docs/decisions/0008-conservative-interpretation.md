# ADR-0008: Conservative interpretation of typed facts

Date: 2026-11-25. Revised 2026-11-26 after the adversarial review (Z-01..Z-17),
the verification pass (ZZ-01..ZZ-11) and the acting-owner decisions recorded
in C2 §0.
Status: Approved 2026-11-26 by the coordinating Claude session acting for
the owner under the delegation of 2026-11-24 (plan/README.md), after two
rounds of adversarial review (findings Z-01..Z-17, ZZ-01..ZZ-11 recorded in
plan/plan.yaml P3-01 notes). Dependencies on C1 behaviour not yet verified
on a device are listed in C2 §2. Normative for C2
(`docs/capabilities/C2.md`).

## Context

C2 turns OCR text into dates, times, amounts, web addresses, email
addresses and phone numbers (design §5). Design §4 fixes the attitude:

- an ambiguous date such as 03/04 needs clarification;
- a date-only value stays a date, not an invented midnight instant;
- no currency is inferred from a symbol without enough context;
- amounts are decimal with a currency code, never binary floating point;
- missing information stays missing.

Design §14 adds that a wrong actionable date is worse than a missed one.
P3-01-R2 fixes the outline of each encoding, and P3-03-R1 fixes the
extractor families. What remained open:
- the exact bytes;
- what counts as ambiguous;
- which symbols and codes map to a currency;
- how far normalisation may go;
- zones, two-digit years, 12-hour times, digit separators and ranges.

The inputs are line-level OCR spans in Latin script. They are untrusted,
often noisy, and have no calibrated confidence (C1). The extractors are
rules, not a model, and run on the device with no dependency (C2-R2). Their
outputs feed C3's event dates, so every interpretation must be one a user
can check against the line in two taps.

## Options considered

### A. How to resolve ambiguity (d/m vs m/d, separators, 12-hour times, currency)

1. **Device locale.** A guess. The phone's locale says nothing about a
   letter from abroad. Rejected (design §14).
2. **Heuristics from the document** (other dates, words such as
   "Rechnung", a code on a nearby line). Still a guess, and the user
   cannot see why a value was chosen. Rejected for C2. Rule versions may
   revisit it with P3-06 data, by ADR.
3. **Abstain on every ambiguity.** Safe, but it loses most numeric dates.
4. **Bounded candidate set, status `CLARIFICATION_NEEDED`, the user
   chooses.** **Chosen.** One tap. A guessed value is never stored as
   established.

### B. Money amounts

1. `Double`. Forbidden by design §4.
2. Minor units in a `Long`. This needs the currency exponent, which is
   unknown when the currency is null, and it loses the printed scale.
   Rejected.
3. **A decimal string with the printed scale**, using `BigDecimal` for
   any arithmetic. **Chosen.**

### C. Currency from a symbol or a nearby code

1. Map every symbol to its most common code. Wrong for every Canadian or
   Egyptian receipt. Rejected.
2. Device locale. Rejected, as in A1.
3. Map a single-code symbol; let a code on the same or an adjacent line
   **decide** a multi-code symbol. This was the first draft. Rejected by
   review Z-04/Z-08/Z-10:
   - `SSP` (statutory sick pay) on a payslip line `SSP £116.75` would
     become South Sudanese pounds;
   - `COFFEE CUP` above `$3.50` would become Cuban pesos;
   - a `USD` that marks another amount would decide this one.
4. **Map a single-code symbol only. A code from context may only *offer*
   candidates, always alongside "not stated". English-word codes are
   never context. Another amount's marker is never context.**
   **Chosen.**

### D. Phone numbers

1. **libphonenumber.** It is a new dependency, and it infers regions.
   Rejected.
2. **Any 7–15-digit run with a separator.** This was the first draft.
   Rejected by review Z-07: it fires on IP addresses, card numbers,
   invoice references and the leftovers of rejected dates.
3. **A hand-written rule that requires `+`, `tel:` or a phone label**,
   with no country inference. **Chosen.**

### E. URLs

1. `java.net.URL`, `URI` or `android.util.Patterns`. Rejected:
   - they resolve, rewrite or backtrack;
   - `Patterns` is an Android dependency in a pure package (P3-03-R3/R4).
2. **A hand-written scanner with a narrow grammar.** **Chosen.**

### F. Zones and instants

1. Attach the device zone. This invents a fact. Rejected.
2. Parse zone designators. Abbreviations are ambiguous, and C3
   (ADR-0009, P4-01) owns zone choice. Deferred.
3. **Store floating local dates and times only. Abstain on a time
   followed by anything that may be a zone.** **Chosen.** No `timezone`
   column exists in C2 (decision Q-9).

## Decision

### §1 General

- **Input.** Every extractor works on the read text of one eligible span
  (C2-R1). A candidate never spans two lines. The only cross-line input is
  the currency context of §5.4.
- **Digits** are ASCII `0`–`9` only (Z-16).
  - A token that contains any other character with
    `Character.isDigit` true (Arabic-Indic, full-width and so on) is
    abstained, and it claims its characters.
  - Letters are ASCII for month names, weekday names, markers and labels.
- **Token boundaries.** A match must not be preceded or followed by a
  character for which `Character.isLetterOrDigit` is true, or by a
  format or combining mark (Unicode Cf, Mn, Mc, Me), read by code point.
  Such a mark is attached, never a boundary, and a token containing one
  abstains (amendment 2026-11-26, A-6). A token led or trailed by one
  abstains visibly too: the abstention covers the mark, and takes the
  kind of the abstention it touches, or no kind when none does
  (`\u200E2026-09-16`, `€\u200D5`; finding EE-04). A token with no ASCII
  digit, `@`, `//` or `www.` is left alone, so emoji sequences are not
  abstained.
- **Spaces.** Where a rule allows a space, any Unicode space separator
  (Zs) or a tab counts. A gap before a zone, an offset or an am/pm
  marker, between a sign and an amount, and around a range joiner is at
  most 4 of them. U+00A0 and U+202F between digits stay grouping (§5.2)
  (amendment 2026-11-26, A-8).
- **Claiming** (C2-R28, Z-17).
  - Rules run in precedence order `URL > EMAIL > DATETIME > DATE > TIME >
    MONEY > PHONE`, with one exception: a `+`-led run is judged before
    MONEY (amendment 2026-11-26, A-9 (a), as refined by EE-01; §7).
  - A rule claims the characters of every token it **recognised**,
    whether it emitted a value or abstained.
  - A lower rule never reads claimed characters.
  - "Recognised" means the scanner found the token's start form:
    - `http`/`https`/`www.` for URLs;
    - `@` with a local part for email;
    - a date shape (§4.1) or a time shape (§4.2);
    - a marker with a number for money;
    - `+`, `tel:` or a label for phones.
- **Performance.** Matching is linear-time scanning; `Regex` is not used
  (C2-R47). Each rule takes under 50 ms on 10,000 adversarial characters
  (P3-03-R3).
- **Rule identifiers.** `rules-date`, `rules-time`, `rules-datetime`,
  `rules-money`, `rules-url`, `rules-email` and `rules-phone`, all `v1`.
  - The reference data is part of the version: the ISO 4217 list, the
    English-word code list, the symbol table, the month and weekday
    names, the zone and allowlist tokens, and the phone labels.
  - Any change bumps the version.
- **`locale`** is `en` when an English month or weekday name was matched,
  otherwise `und`.

### §2 Canonical JSON (`valueJson`)

- **Form.** UTF-8 JSON objects, with keys in the order given below and no
  insignificant whitespace.
- **No numbers.** There is no number type anywhere: every scalar is a
  string or `null`.
- **Escaping** is exact, so equal values are equal bytes:
  - `"` becomes `\"` and `\` becomes `\\`.
  - U+0008, U+0009, U+000A, U+000C and U+000D become `\b`, `\t`, `\n`,
    `\f` and `\r`.
  - Every other code point below U+0020 becomes `\u00XX`, with **lowercase**
    hex.
  - `/`, U+007F and everything at or above U+0080 are written as raw UTF-8,
    never escaped.
  - A string containing an unpaired surrogate is not a valid value and is
    never written. The candidate is abstained.
- **Serialisation.** A writer serialises from the typed value. It never
  re-serialises parsed JSON.

Shapes:

- `DATE`: `{"date":"YYYY-MM-DD"}`, proleptic Gregorian, with the year from
  1000 to 9999.
- `TIME`: `{"time":"HH:mm"}`, or `{"time":"HH:mm:ss"}` when seconds were
  printed. `HH` runs from 00 to 23.
- `DATETIME`: `{"date":…,"time":…}`.
- `MONEY`: `{"amount":"…","currency":"XXX"|null,"rawSymbol":"…"|null}`.
  - `amount` is an optional `-`, then integer digits with no leading zero
    (except `0`), then optionally `.` and the fraction digits exactly as
    printed.
  - `rawSymbol` is the symbol as printed, or `null` when only a code was
    printed.
- `URL`, `EMAIL`, `PHONE`: `{"normalized":"…","raw":"…"}`. `raw` is exactly
  the evidence slice.
- Candidates: `{"candidates":[v1,…]}`, with 2 to 64 values of the kind.
  - **Ordering**:
    - dates, times and date-times chronologically;
    - money by numeric amount, then by `amount` string, then by
      currency, with `null` first and codes alphabetical.
  - **Duplicates** are removed. A set that reduces to one value is stored
    as that value.
  - **Size.** Rules v1 produce at most 6.

### §3 `canonicalText`

| Kind | `canonicalText` |
| --- | --- |
| `DATE` | `YYYY-MM-DD` |
| `TIME` | `HH:mm[:ss]` |
| `DATETIME` | `YYYY-MM-DD HH:mm[:ss]` |
| `MONEY` | `amount` + space + (`currency` ?: `rawSymbol`) |
| `URL`, `EMAIL`, `PHONE` | `normalized` |
| candidates | each candidate's text, joined by ` \| ` |

### §4 Dates and times

#### §4.1 Dates (`rules-date-v1`)

**ISO and year-first forms.** `YYYY-MM-DD`, `YYYY/MM/DD` and
`YYYY.MM.DD`, with 1–2 digit month and day. These are unambiguous.

**Numeric `A/B/YYYY` forms** (separator `/`, `-` or `.`, the same one
twice; `A` and `B` of 1–2 digits):
- Reading 1 is day `A`, month `B`. Reading 2 is month `A`, day `B`.
- Both readings valid and different: a candidate pair, for example
  `03/04/2026` gives `2026-03-04 | 2026-04-03`.
- Exactly one valid: that value, for example `13/04/2026` gives
  `2026-04-13`.
- Neither valid: abstain. For example, `31/04/2026` is abstained, and it
  still claims its characters.
- `04/04/2026` is a single value.

**Two-digit years, `A/B/C` with `C` two digits** (decision Q-4, Z-12):
- The candidates are every valid date among:
  - day `A`, month `B`, year `19C` and `20C`;
  - month `A`, day `B`, year `19C` and `20C`;
  - **year-first**: year `19A` and `20A`, month `B`, day `C`.
- That is at most 6. A two-digit-year form is never a single value.
  - `03/04/26` gives `1903-04-26 | 1926-03-04 | 1926-04-03 | 2003-04-26 |
    2026-03-04 | 2026-04-03`.
  - `25/12/30` gives `1925-12-30 | 1930-12-25 | 2025-12-30 | 2030-12-25`
    (day-month and year-first; month 25 is invalid).

**English month names.** Full names and three-letter abbreviations, plus
`Sept`, in any case, with an optional `.`:
- forms `D Month YYYY`, `Month D, YYYY`, `Month D YYYY` and `D-Mon-YYYY`;
- the ordinals `st`, `nd`, `rd` and `th`;
- these are unambiguous.

**Weekday names** (Z-12). An English weekday (`Tue`, `Tuesday`, with an
optional `,`) immediately before a date **filters** its candidates:
- only candidates falling on that weekday remain;
- one left: a single value, except that a two-digit year never gives a
  single value: when the weekday leaves one, it is abstained (amendment
  2026-11-26, A-3);
- several left: candidates;
- none left: abstain;
- a single value that does not match is abstained;
- a weekday alone is abstained.

**Date ranges are abstained** (Z-12, decision). Each of these is
recognised, abstained and claimed as a whole:
- `D–D Month YYYY` and `D-D Month YYYY` (`3–5 April 2026`);
- `D Month – D Month YYYY`;
- `A–B/M/YYYY`;
- two dates joined by `–`, `-`, `to`, `until` or `till`;
- `D Month YYYY` (or `D Mon YYYY`) **preceded by** `<digits>` and one of
  `–`, `-`, `to`, `until`, `till`, `and` or `&`, with optional spaces
  (ZZ-06). Examples: `3 to 5 April 2026`, `between 3 and 5 April 2026`,
  `3 & 5 April 2026`. The whole `<digits> … D Month YYYY` is claimed.

A range candidate type is not added in v1. A range needs both ends and a
meaning (stay, event, validity) that C3 owns.

**Also abstained:**
- no year (`3 April`, `03/04`; decision Q-10);
- relative dates;
- year and month only;
- Roman-numeral months;
- non-English month names.

#### §4.2 Times (`rules-time-v1`)

**24-hour form.** `HH:mm` or `HH:mm:ss`, with `:`.
- Hours 13–23 are unambiguous, as is a leading-zero hour (`00`–`09`).

**12-hour form.** `h[:mm[:ss]]` followed by `am`, `pm`, `a.m.` or `p.m.`
(any case, optional space).
- Hours 1–11 are unambiguous: `2:30 PM` gives `14:30`, `10:00 AM` gives
  `10:00`.
- Hour 12 gives candidates `00:mm | 12:mm` (decision Q-5).

**No marker.** An hour 1–12 without a leading zero (`2:30`, `10:30`,
`12:15`) gives candidates `h:mm | h+12:mm`, or `00:mm | 12:mm` for 12
(decision Q-5).

**Ranges** (`T1–T2`, `T1 - T2`, `T1 to T2`) produce two separate TIME
facts, and each is interpreted **only from its own text** (Z-05).
- A marker after `T2` does not apply to `T1`.
- So `11:30 - 1:30 pm` gives `11:30 | 23:30` and `13:30`.
- **A range abstains as a whole** (ZZ-03). If anything after `T2` makes
  `T2` abstain under §4.3 (a zone, an uppercase token or a parenthesised
  group), `T1` abstains too, and the whole range is claimed. For example,
  `14:30 - 16:30 CET` gives nothing.

- Any abstention of `T1` or `T2`, not only a zone, abstains the whole
  range: `10:00 - 25:00` gives nothing (amendment 2026-11-26, A-11).

**Abstained:**
- `24:00`;
- a minute or second over 59;
- `.` before an am/pm marker (`2.30 pm`) or `h` (`14h30`) as the
  separator, claimed (amendment 2026-11-26, A-11); a bare `14.30` is not
  a time;
- fractional seconds: `.` or `,` and a digit after the time
  (`14:30:00.000Z`), over the whole token (amendment 2026-11-26, A-2);
- a bare `1430`;
- §4.3.

A time never starts directly after `+`, `-`, `±` or a dash or minus
look-alike (`UTC+05:30`, `Offset +02:00`), unless that character joins it
to the time just before (`9am-5pm`) (amendment 2026-11-26, A-2).

#### §4.3 Date-times and zones (`rules-datetime-v1`)

**When a DATETIME is emitted.**
- A date and a time in the same span, separated only by whitespace, `,`,
  `at`, `@`, `T` or `-`, in either order, with **both unambiguous**, form a
  DATETIME. The parts are not emitted separately.
- If either part is ambiguous, they are emitted separately. No
  cross-product is formed.

**What may follow a time** (Z-06). The time rule first consumes an
am/pm marker. It then looks at the next token, after at most 4 space separators or
tabs (amendment 2026-11-26, A-8),
and **abstains** on the time (and on any date-time containing it) when
that token is one of these:
1. `Z`, `Zulu` in any case, `±HH:MM`, `±HHMM` or `±HH` (the sign `+`,
   `-`, U+2212 `−` or `±`);
2. a token of 2 to 5 ASCII letters written **all in uppercase** that is
   not on the allowlist below. This covers `CET`, `UTC`, `IST`, `HRS`…
   and any unknown abbreviation. An offset of 1–2 or 4 digits, with
   optional `:MM`, directly after the word is part of the token
   (`GMT+1`, `GMT+01:00`, `UTC-5`; amendment 2026-11-26, A-2);
3. a parenthesised group starting with a letter, `+` or `-`, as in
   `14:30 (local)`, `(CET)` or `(+01)`.

The allowlist of words that are not zones (v1): `TO`, `TILL`, `UNTIL`,
`AND`, `OR`, `AT`, `ON`.
- An allowlisted word does not stop the time from being read. `10:30 TO
  11:30` gives two times.
- Lowercase and mixed-case words (`to`, `Uhr`) are not zone candidates
  and do not stop the time.
- **What survives an abstention.** When a date-time abstains for this
  reason, a date that is a separate token is still emitted on its own.

No zone is ever stored or attached.

### §5 Money (`rules-money-v1`)

#### §5.1 What is money

A number is money only when a **marker** sits in marker position: directly
before or directly after the digits, with at most one space between them
(any space separator; amendment 2026-11-26, A-8).
A marker is one of these:
- a symbol from §5.3;
- an ISO 4217 code from the pinned **active** list (List One, the
  version used is stated in the source constant), in uppercase.

**English-word codes as markers** (§5.5 normative and informative lists;
ZZ-04, decision Q-13):
- An English-word code that is the **only** marker counts only when the
  number has printed fraction digits. `TRY 45.00` and `45.50 ALL` are
  money. `PEN 2`, `TOP 10` and `ALL 3` are not, and are not claimed.
- An English-word code **never resolves a symbol**. When it marks the same
  digits as a multi-code symbol whose codes include it, the result is the
  candidate set of §5.4, with that code plus "not stated". So `$3.50 CUP`
  gives `3.50 $ | 3.50 CUP`. When the symbol's codes do not include it,
  the amount is abstained, as below.

**Rules for the marker:**
- A bare number is never money.
- When a symbol and a code that is not on the English-word lists both
  mark the same digits (`$10 USD`), the amount takes the code if the code
  is among the symbol's codes. Otherwise (`$ 10 EUR`) it is abstained.
- A code that sits next to the **symbol** rather than the digits
  (`SSP £116.75`, `USD $5`) is not a marker. It is context under §5.4.

**Signs** (Z-13; amendment 2026-11-26, A-4):
- A lone ASCII `-` touching the marker or the digits, with no space
  (`-€5`, `€-5`, `-5 €`), and not preceded by a digit with at most one
  space between, makes the amount negative. This is the only sign read.
- A `-` that follows digits is never a sign.
- These forms are recognised, abstained and claimed:
  - any other hyphen, dash or minus look-alike (U+2010–U+2015, U+2212
    `−`, U+FE58, U+FE63, U+FF0D) in a sign position: `−€5.00`,
    `–€5.00`, `€−5.00`, `−5 €`;
  - a sign with up to 4 space separators between it and the marker or
    the digits: `- €5`, `-  €5.00`, `€ –5.00`, `EUR -33.10`;
  - a dash between the digits and a marker after them (`5.00− €`);
  - a trailing minus, any dash-like character up to 4 space separators
    after the amount (`25.00-`, `25.00 -`, `€25.00 −`);
  - `25,-`, `25.-` and `25,--`;
  - two signs (`--€5`, `-−€5`) or `±` before the amount (finding EE-05);
  - parentheses, with or without inner spaces (`( €5.00 )`), and `CR` or
    `DR` in any case directly after the amount or after one space
    separator (`€5.00 Cr`, `€5.00\u00A0dr`; ruling 2026-11-26);
  - an amount range (`10-20 €`, `10–20 €`, `€10–20`, `10 to 20 EUR`).
    The joiner is a dash-like character or `to`, `until`, `till` or
    `and`, with up to 4 space separators each side, and the range takes
    in the partner's own markers: `€10–€20`, `€10 - €20`, `10 € - 20 €`,
    `€10 to €20` (amendment 2026-11-26, A-7). A partner may carry a lone
    sign (`-€5 to -€10`, `€-5 - €-10`), and the range runs on over further
    joiners (`€10-€20-€30`, `€10 and €20 and €30`) (finding EE-03). `and`
    joins two amounts with the same marker (`between €10 and €20`), or a
    bare number before an amount marked after its digits (`10 and 20 €`),
    so `$5 and £10` and `€10 and 20` stay amounts.
- A **currency pair**, a List One code joined by `/` (at most one space
  separator each side) to another (`EUR/USD`), is not a marker: an amount
  beside it is abstained (`EUR/USD 1.08`, `1.08 EUR/USD`). Neither code
  is §5.4 context (ruling 2026-11-26).
- A code beside a signed number (`USD -33.10`) is a marker, never §5.4
  context (amendment 2026-11-26, A-10).

#### §5.2 Digits and separators

The separators are `,`, `.`, space, U+00A0, U+202F (narrow no-break space)
and `'`.
- **`,` and `.` both present.** The last one is the decimal separator, and
  the other must form valid groups of 3. `1,234.56` and `1.234,56` both
  give `1234.56`.
- **One of `,` or `.` repeated.** It is grouping, in groups of 3:
  `1,234,567`.
- **One `,` or `.` followed by 1–2 digits.** It is the decimal separator.
  Followed by 4 or more digits, the number is abstained.
- **One `,` or `.` followed by exactly 3 digits** gives two candidates, the
  decimal reading and the grouping reading: `1,234 €` gives
  `1.234 EUR | 1234 EUR`. When the grouping reading cannot exist (a first
  group over 3 digits, a first group led by `0`, or another grouping
  character), the number is abstained: `1234,567`, `0.500`, `1'234,567`
  (amendment 2026-11-26, A-10).
- **U+202F, U+00A0 and `'`** are grouping only, in groups of 3: `1'234.50
  CHF` gives `1234.50 CHF`.
- **An ASCII space between digit groups is ambiguous** (Z-13). It may be
  grouping, or a quantity followed by a price. When the groups before the
  marker are separated by single spaces and every group after the first
  has exactly 3 digits, the candidates are:
  - the grouped reading;
  - the reading of only the group adjacent to the marker (the left group
    for a marker before the digits, the right group for one after).

  So `1 234 €` gives `234 EUR | 1234 EUR`. Any other spacing is not an
  amount beyond the adjacent group. "Space" here is any one space
  separator or tab (A-8); U+00A0 and U+202F inside a dense number stay
  grouping only. The grouped reading joins the printed digits before
  dropping leading zeros (amendment 2026-11-26, A-1).
- **Zero-led groups** (finding EE-06). A grouped number whose first group
  starts with `0` is abstained (`€0 001`, `0 500 €`). An adjacent group
  that starts with `0` is never a reading of its own; the first group's
  reading takes its place. The candidates are therefore:

  | Text | Candidates |
  | --- | --- |
  | `€5 000` | `5 EUR \| 5000 EUR` |
  | `1 000 €` | `1 EUR \| 1000 EUR` |
  | `12 005 €` | `12 EUR \| 12005 EUR` |
  | `1 000 000 €` | `1 EUR \| 1000000 EUR` |
  | `€0 001` | abstained |
- **A fraction after space groups** (finding EE-02). When the last group
  carries a decimal mark (`5 000.00 €`, `€5 000.00`, `$1 234.56`,
  `€12 345,67`), the whole run is abstained, prefix and suffix alike.
- **Limits.** At most 15 integer digits and 3 fraction digits, or the
  number is abstained.

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

Multi-code symbols:

| Symbol | Codes |
| --- | --- |
| `$` | USD, CAD, AUD, NZD, HKD, SGD, MXN, ARS, CLP, COP, TWD, JMD, TTD, BSD, BBD, BZD, BMD, BND, FJD, GYD, KYD, LRD, NAD, SBD, SRD, XCD, DOP, CUP |
| `£` | GBP, EGP, FKP, GIP, LBP, SHP, SSP, SYP |
| `¥` | JPY, CNY |
| `₩` | KRW, KPW |
| `kr` | DKK, ISK, NOK, SEK |

- `C$` (CAD or NIO) is excluded.
- `Fr`, `R`, `Rs`, `¢` and `L` are not markers in v1.

#### §5.4 Currency context for a multi-code symbol (Z-04, Z-08, Z-10)

**Where codes are looked for.** In the same span and in the eligible
spans with ordinal ±1 in the same reading.

**A code found there is a qualifying context code only if all of these
hold:**
1. it is in uppercase and in the symbol's code list;
2. it is not on the English-word list (§5.5);
3. it is not itself in marker position for another number on its line
   (**another amount's own marker is never context**);
4. it is not half of a currency pair (`EUR/USD`; ruling 2026-11-26).

**Result:**

| Qualifying context codes found | Value |
| --- | --- |
| none | a single value with `currency: null` and `rawSymbol` as printed, status `UNREVIEWED` |
| 1 or 2 distinct | candidates: one value per code, **plus** the `currency: null` value ("not stated"), status `CLARIFICATION_NEEDED` |
| more than 2 distinct | as for none |

- **No context code ever yields a definite currency.**
- **Combining with separator candidates.** When the separator rule (§5.2)
  also gives candidates, the set is their product, which is at most 6.
- **Evidence.** Every adjacent span that was **inspected** is recorded as
  a `CURRENCY_CONTEXT` evidence row, whether or not a code was found
  there. That gives at most two rows. The edge to each such span means a
  later correction of it stales the fact (C2 §4.3, §9).
  - When the code was found, the row's offsets locate it.
  - Otherwise the row's offsets are NULL.
  - The same span is already the `VALUE` row.

**Normative examples:**

| Text (`/` separates lines) | Result |
| --- | --- |
| `SSP £116.75` | `116.75 £ \| 116.75 SSP`, CLARIFICATION_NEEDED |
| `COFFEE CUP` / `$3.50` | `3.50 $` (currency null), UNREVIEWED. `CUP` is an English word |
| `Total $45.00` / `USD 33.10` | `45.00 $` (null), UNREVIEWED, because `USD` is the other amount's marker; and `33.10 USD` |
| `Prices in AUD` / `$12.00` | `12.00 $ \| 12.00 AUD`, CLARIFICATION_NEEDED |
| `Prices in CAD` / `$12.00` | `12.00 $` (null), UNREVIEWED: `CAD` is on the §5.5 list |
| `$10 USD` | `10 USD` |
| `$3.50 CUP` | `3.50 $ \| 3.50 CUP`, CLARIFICATION_NEEDED (ZZ-04) |
| `TRY 45.00` | `45.00 TRY` |
| `PEN 2`, `TOP 10`, `ALL 3` | nothing (an English-word code alone needs fraction digits) |
| `$ 10 EUR` | abstained |

#### §5.5 ISO codes that are English words

Only a code in the multi-code symbol table (§5.3) can ever be context, so
this list is what matters normatively.

**Normative list (v1).** These codes are never context. In marker position
directly beside digits they are still markers:

`CAD`, `COP`, `CUP`, `GIP`, `NAD`, `SHP`

**How it was derived.** Each of the 45 codes in the §5.3 multi-code table
was checked by hand for being any of these:
- an English dictionary word: cad, cop, cup, gip;
- common slang: nad;
- an abbreviation likely in uppercase on receipts and letters: `SHP`, for
  shipping.

**Deliberately not listed:**
- `SSP` (statutory sick pay on UK payslips). It is a real currency for
  `£`. A context code only offers candidates next to "not stated", so
  leaving it off costs one tap. Listing it would hide South Sudanese pounds
  entirely.
- `DOP`, `BSD` and `KYD`, for the same reason.

**Informative: other active codes that are English words or common
abbreviations.** These are `ALL`, `BAM`, `BOB`, `GEL`, `MAD`, `MOP`, `PEN`,
`RUB`, `SOS`, `TOP`, `TRY`, `AMD`, `PHP` and `RON`.
- They are in no symbol table, so they can never be context.
- Like every code, they are markers only in marker position (`TRY 45.00`).
- P3-06 records false markers among them.

**Source of the code list.** The marker list is ISO 4217 List One (active
codes) at the pinned edition stated in the source constant. It is not
`java.util.Currency.getAvailableCurrencies()`: on JDK 21 that returns 233
codes, including withdrawn ones such as `EEK`, `SIT`, `ESP` and `STD`,
which are never markers.

**Changes.** P3-06 records false context and false markers. Any change to
either list bumps `rules-money` (C2 Q-13).

### §6 URLs and email addresses

#### §6.1 URLs (`rules-url-v1`)

- **Start.** `http://` or `https://` (any case), or `www.` at a token
  start.
- **Host.** ASCII labels of `[A-Za-z0-9-]` separated by `.`, at least two
  labels, then an optional `:port` of 1–5 digits.
- **Rest.** RFC 3986 unreserved and reserved characters, and `%`.
- **End.** At whitespace or any other character.
  - Trailing `.,;:!?'"` is trimmed.
  - An unmatched closing `)]}` is trimmed.
- **Abstained, and claimed** (§1):
  - userinfo: `https://bank.example@evil.example` yields no URL and, being
    claimed, no EMAIL either (Z-17);
  - a non-ASCII or `xn--` host;
  - other schemes (`javascript:`, `data:`, `file:`, `intent:`, `ftp:` and
    others);
  - more than 2,048 characters.
- **Normalisation.** Lowercase the scheme and host only. Nothing is added,
  decoded or resolved.

#### §6.2 Email addresses (`rules-email-v1`)

- **Local part.** A dot-atom of `[A-Za-z0-9._%+-]`, 1–64 characters, with
  no leading, trailing or doubled dot. Quoted local parts are abstained.
- **Domain.** ASCII labels, at least two, with a final label of 2 or more
  letters. A label never starts or ends with `-` and is never an IDNA
  `xn--` label. A domain directly followed by `_`, `@`, a letter, a digit
  or a mark is attached and abstained (`user@example.com_evil`)
  (amendment 2026-11-26, A-10).
- **Total length.** At most 254 characters.
- **`mailto:`** is consumed.
- **Normalisation.** The local part is unchanged; the domain is
  lowercased.

### §7 Phone numbers (`rules-phone-v1`)

**Required prefix** (Z-07). A run is a phone candidate only when one of
these introduces it:
- a leading `+`;
- `tel:`;
- a label immediately before it, with optional `:`, `.` or one space. The
  label must be a **whole token** (ZZ-05): it starts at the start of the
  text or after a character that is not a letter, and the next character
  is not a letter. So `Depth 1200-1500`, `Halifax 1234-5678` and `Hotel
  0221 123456` are not phones. The
  labels (case-insensitive, English) are `Tel`, `Telephone`, `Phone`,
  `Ph`, `Mobile`, `Mob`, `Cell`, `Fax` and `Call`.

**Maximal run.**
- It starts at the `+`, or at the first digit after `tel:` or the label.
- It extends over digits and over single separators: one space, `-` or
  `.`.
- It also takes in a parenthesised group of 1–4 digits.
- After a separator it continues only if a digit or `(` follows.
- It ends at the first character that cannot extend it.

The whole maximal run is judged. A sub-run is never taken.

**Accepted when** the run has 7 to 15 digits in all and exactly one
leading `+` at most. A `+`-led run also extends over `,`; a `+`-led run
containing `.` or `,` is abstained (`+1234567.89`, `+12.345.678`)
(amendment 2026-11-26, A-9).

**A `+`-led run beside a currency marker** (A-9 (a), refined by finding
EE-01). `+`-led runs are judged before MONEY. A run of 7 to 15 digits
with a currency marker within one space separator on either side
(`+1234567 EUR`, `€ +1234567`, `+1 555 123 4567 USD`) could be a phone or
a signed amount: it is claimed and abstained as PHONE with
`AMBIGUOUS_FORM`, and gives no value of either type. A marker that has a
digit on its far side marks that number instead (`12.00 € +1 555 123
4567` keeps the phone). A run with fewer than 7 digits is left to MONEY
(`+5 €` is `5 EUR`); one with more than 15 digits is abstained by MONEY
as over its limit.

**Abstained** (and claimed):
- a dotted quad: four groups of 1–3 digits separated by `.`, for example
  `192.168.1.10`;
- a card-like grouping: three or more groups of exactly 4 digits, or 13–19
  digits in groups of 4. For example, `4111 1111 1111 1111`;
- any run containing `/` or `:`;
- any run overlapping characters claimed by a date, time or amount rule,
  **including** characters that rule abstained on (§1);
- a pure digit run with no prefix: no prefix, no phone.

**Normalisation.**
- The `+` is kept if printed, followed by all the digits.
- `(0)` is dropped only after a leading `+`.
- Nothing is added.

**Examples:**

| Text | Result |
| --- | --- |
| `Tel: 01632 960123` | `01632960123` |
| `+44 (0)20 7946 0018` | `+442079460018` |
| `Ref 1234 5678 9` | nothing |
| `Depth 1200-1500`, `Halifax 1234-5678`, `Hotel 0221 123456` | nothing (the label is not a whole token) |

## Consequences

- **User effort.** More values need one tap: numeric dates, two-digit
  years (up to 6 candidates), times without a marker, three-digit groups,
  space-grouped amounts, and `$`/`£`/`¥` near a code. In return, no
  extracted value is a silent guess. P3-06 measures the clarification
  rate per rule version. Thresholds change only by ADR.
- **Recall is lower.** These are all abstained:
  - dates without a year;
  - date ranges;
  - unlabelled phones;
  - zoned or annotated times;
  - non-English month names;
  - bare numbers;
  - amount ranges;
  - `25,-`.

  The design accepts this trade (precision ≥ 0.97, recall ≥ 0.85, with
  TIME and DATETIME gated as DATE: decision Q-3).
- **No dependency.** No new dependency, permission or Android API. The
  reference tables are versioned source constants.
- **Inert values.** Normalised forms never reach an API that could
  resolve or open them.
- **Residual: mixed-case zone words** (ZZ-07, C2 §15). Only uppercase
  tokens and brackets after a time cause abstention. `9:00 Eastern`,
  `15:00 London time` and `10am Pacific` therefore yield a floating local
  time, although the text names a zone. The UNREVIEWED status and the
  evidence line mitigate it. C3 must have the user choose a zone
  (ADR-0009) before anything is scheduled. A later rule version may add a
  zone-word list, measured by P3-06.
- **Residual: English-only phone labels** (decision Q-14). Labels in other
  languages (`Tél`, `Telefon`, `Tfno`) are not recognised, so such numbers
  are missed. This is a stated recall limit. A later capability may add
  localised labels by ADR.
- **Residual risk** (C2 §15). A digit-for-digit OCR confusion (`1`/`7`,
  `3`/`8`, `$`/`5`) still produces a plausible valid value. Only the
  UNREVIEWED status and the one-tap evidence link mitigate it.
- **C3 and C4 obligations.**
  - C3 inherits floating local dates and must choose a zone explicitly
    (ADR-0009). It also owns date ranges and relative dates.
  - C4 inherits `currency: null` and must have the user confirm a
    currency before a total is confirmed (P6-01).

## Review disposition (2026-11-26)

Decided by the coordinator under the 2026-11-24 delegation:
- `12 am/pm` and times without a marker need clarification (Q-5).
- Two-digit years give both centuries plus year-first (Q-4).
- A date without a year is abstained (Q-10).

Changed by review findings:

| Finding | Change |
| --- | --- |
| Z-04/Z-08/Z-10 | §5.4–§5.5 |
| Z-05 | §4.2 ranges |
| Z-06 | §4.3 tokens after a time |
| Z-07 | §7 |
| Z-12 | §4.1 ranges, year-first and weekday filtering |
| Z-13 | §5.1 signs and ranges, §5.2 spaces |
| Z-16 | §1 digits |
| Z-17 | §1 claiming |
| testability | §2 escaping |

Verification pass (2026-11-26), changed by findings: ZZ-03 (§4.2 a range
abstains as a whole), ZZ-04 (§5.1 English-word markers, examples), ZZ-05
(§7 whole-token labels), ZZ-06 (§4.1 `3 to 5 April 2026`), ZZ-07
(Consequences, residual). Decided by the coordinator under the 2026-11-24
delegation: Q-13 (the §5.5 lists are accepted with the ZZ-04 marker rule)
and Q-14 (no labels in other languages in C2). No question remains open.

## Amendments 2026-11-26 (decided by the coordinating session under the 2026-11-24 delegation, after the P3-03 adversarial review)

The P3-03 adversarial review (findings E-01 to E-10 and 23 specification
questions) ran its own corpus against the first implementation. The
coordinating session decided the changes below under the 2026-11-24
delegation. Nothing had shipped or been recorded, so every rule stays
`v1`. Each change is also written into the section named.

| Ref | Finding | Change | Section |
| --- | --- | --- | --- |
| A-1 | E-01 | Space-grouped amounts join the printed digit groups before dropping leading zeros (`€5 000` gives `5 EUR \| 5000 EUR`, not `50`). | §5.2 |
| A-2 | E-02 | Fractional seconds (`.`/`,` and a digit after a time) abstain the whole token. No time starts directly after `+`, `-`, `±` or a look-alike unless that character joins it to the previous time. A zone word takes a directly following offset (`GMT+01:00`, `UTC-5`). Offsets also accept U+2212. A date joined by `T` abstains with its time for any reason, not only a zone. | §4.2, §4.3 |
| A-3 | E-03 | A two-digit year never yields a single definite date. When weekday filtering leaves one reading, the date abstains. This resolves the earlier conflict between "never a single value" and "one left: a single value". | §4.1 |
| A-4 | E-04, Q10, EE-05 | Only a lone ASCII `-` touching the amount is a sign. Look-alikes, spaced signs (up to 4 space separators), two signs or `±`, a dash before a marker after the digits, a trailing dash-like character, parentheses with inner spaces and `CR`/`DR` in any case abstain with `SIGN_OR_CREDIT`. | §5.1 |
| A-5 | E-05 | `-` followed by a digit continues a numeric date run (`2026-09-16-17`, `5512-03-04-2026` abstain). A numeric token does not start after `-` that follows a digit run, unless that run is a standalone 1–2 digit day (`3-5 April 2026` stays a range); `Ref 123-2026-09-16` gives nothing. | §4.1 |
| A-6 | E-06, EE-04 | Unicode categories Cf, Mn, Mc and Me are attached characters, never boundaries, read by code point; a token containing, led by or trailed by one abstains, and the abstention covers the mark. | §1 |
| A-7 | E-07, EE-03 | A money range extends over the partner's markers, sign and number, and over further joiners, with `to`, `until`, `till` or `and` (same marker, or a bare number before an amount marked after its digits) as joiners. | §5.1 |
| A-8 | E-08, Q7 | Zone, offset and am/pm lookahead skips at most 4 Unicode space separators or tabs. Any space separator counts wherever a rule allows a space (marker gaps, date gaps, range gaps, phone labels); U+00A0 and U+202F between digits remain grouping. | §1, §4.3, §5.1 |
| A-9 | E-09, EE-01 | (a) `+`-led runs are judged before MONEY. A run that §7 accepts is emitted and claimed, except that a run of 7–15 digits with a currency marker within one space separator either side abstains (PHONE, `AMBIGUOUS_FORM`), so `+1 555 123 4567 USD` and `+1234567 EUR` give nothing. (b) A `+`-led run containing `.` or `,` abstains as PHONE. (c) Three dotted numeric groups (`Version 1.2.33`, `10.4.21`) remain date candidates: a stated residual of v1. | §1, §7 |
| A-10 | E-10, Q11 | Email labels never start or end with `-` and are never `xn--`; a domain followed by `_` is attached. A code beside a signed number is not currency context. One separator before 3 digits whose grouping reading is impossible (`1234,567`, `0.500`, `1'234,567`) abstains. | §5.1, §5.2, §6.2 |
| A-11 | Q16, Q20 | `H.MM` before an am/pm marker and `HhMM` are claimed and abstained. Any abstention of either time in a range abstains the whole range. | §4.2 |
| A-12 | EE-02, EE-06 | Any single space separator groups digits. A space-grouped amount ending in a fraction abstains whole. A zero-led first group abstains; a zero-led adjacent group is replaced by the first group as the alternative. | §5.2 |
| A-13 | rulings | `Zulu` is a zone word. A currency pair (`EUR/USD`) is neither a marker nor context. `CR`/`DR` match in any case. | §4.3, §5.1, §5.4 |

Other questions keep the behaviour recorded in the P3-03 verification
record. Q2 (`D Month, YYYY` with a comma), Q6 (`09:00-17:00` read as an
offset) and Q12 (`Tel: (020)…` without `+`) are deferred to rules v2.

**Stated residuals of v1.**
- Three dotted numeric groups are date candidates (A-9 (c)).
- A dash-like character within 4 spaces of an amount abstains it, so
  a menu line such as `Coffee – €3.50` gives no amount.
- `14:30 +1` and `14:30 -5` (a sign and one digit after a space) are not
  read as offsets, and `9:00 Eastern` is not read as a zone. In each case
  the time is still read.
- A `+`-led phone beside a currency marker gives nothing
  (`+44 20 7946 0018 USD`), a recall loss accepted to avoid a sign flip.
- A zone after the second time of a list joined by `and` or `,`
  (`10:00 and 11:00 UTC`) abstains only that time.

## Proposed amendments 2026-11-28 (P3-06 follow-up; not yet decided)

Raised by the P3-06 facts evaluation (image `img-30`, ML Kit read `Coffee – €3.50`
as `Coffee-€3.50`, giving a wrong definite MONEY `-3.50 EUR`). Proposed here, in the
format of the 2026-11-26 table, for the owner to decide. Until decided, the code
follows the proposal.

| Ref | Finding | Change | Section |
| --- | --- | --- | --- |
| A-14 (accepted 2026-11-28 by the coordinating session under the owner delegation of 2026-11-24) | F3 (P3-06 image eval) | A lone `-` directly after a preceding letter or attached mark (`Coffee-€3.50`, `x-€3.50`, `Item-3.50 EUR`, `EUR-5.00`) is read as a separator, not a sign. The sign is then unclaimed, so the amount abstains with `SIGN_OR_CREDIT` (neither a negative nor a positive definite value). Signs after a space, after a symbol (`€-5`) or at the start (`-€5`) keep the A-4 reading. A `-` after a digit keeps the A-4 rule (never a sign). The Unicode dash forms already abstain (A-4). | §5.1 |

