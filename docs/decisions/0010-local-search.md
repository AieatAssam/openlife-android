# ADR-0010: Local text search inside the vault

Status: accepted for P2-08 (closes F-22)

## Context

Design §5 permits local search from C1. The text to search is the OCR span
text (`ocr_spans.text`) and the user's corrections
(`ocr_user_revisions.correctedText`). Both are personal content. The vault
is a SQLCipher database (`net.zetetic:sqlcipher-android` 4.19.0), opened
through Room 2.8.5. Two constraints apply. No plaintext copy of the content,
and so no plaintext index, may exist outside the encrypted database
(THREAT_MODEL). Deleting a Source must remove everything derived from it
(VS-2, C1-R7).

## Engine support: evidence read from the pinned artefact

This evidence was read from the artefact on 2026-11-25, without running it.

- AAR `sqlcipher-android-4.19.0.aar`, SHA-256
  `3f2aeebb584157baf145805dd8e2118a093632fbfa3dcb72a39e43a2c56b41fe`
  (the hash pinned in Gradle verification metadata).
- Each of the four ABIs ships a `libsqlcipher.so`:
  - arm64-v8a, SHA-256 prefix `da51355b6c455150`;
  - armeabi-v7a, `95411f7303f923a2`;
  - x86, `7e48f1980e5f6e2a`;
  - x86_64, `5f41c2f2cf349c71`.
- The compile-option strings in every `.so` include `ENABLE_FTS5`,
  `ENABLE_FTS4`, `ENABLE_FTS3`, `HAS_CODEC` and
  `DEFAULT_RECURSIVE_TRIGGERS`.
- The library also contains the FTS5 symbols `unicode61`,
  `remove_diacritics`, `bm25`, `snippet`, `highlight` and `trigram`.
- The bundled SQLite is 3.53.4, with source id
  `2026-07-24 19:02:57 bf7c7f30…`.

A runtime check on SQLCipher (`FtsAvailabilityTest.sqlcipherBuildSupportsFts5`)
is written and compiles. It is **implemented; unverified (gap — pending
physical-device run)**. Until it runs, engine support rests on the artefact
evidence above. The code does not rely on that evidence alone (see the
fallback below).

## Decision

### Index

- Schema v5 (`MIGRATION_4_5`) adds two FTS5 **external-content** tables:
  - `ocr_text_fts(text)`, with `content='ocr_spans'`;
  - `ocr_correction_fts(correctedText)`, with `content='ocr_user_revisions'`.

  Both use `tokenize='unicode61 remove_diacritics 2'`. The tables store only
  the index, not a second copy of the text, and they live in the same
  encrypted file.
- Six `AFTER INSERT/UPDATE/DELETE` triggers keep the index in step with the
  content tables. Deletes use the FTS5 `'delete'` command. The migration
  ends with `'rebuild'`, which indexes the rows that already exist, and then
  re-asserts the READY invariant triggers, as every migration does.
- **Only the triggers write the index (VS-2).** No repository statement
  inserts or deletes index rows, and `DeletionRepository` does not mention
  the index. A row that leaves a content table, by any route, also leaves
  the index: a repository delete, `ON DELETE CASCADE` from a source or a
  revision, or a reset. `SearchIndexBoundaryTest` enforces this.
- The tables and triggers are not Room entities. Room supports only FTS3/4
  entities, and its validation covers only entity tables, so `5.json`
  equals `4.json` except for `"version"`. A fresh database gets the same
  index from `readyInvariantCallback.onCreate`, which calls the same
  `SearchIndex.create`.
- **Secure-delete (review W-01).** Both tables are created with the FTS5
  `secure-delete` option (`INSERT INTO <t>(<t>, rank) VALUES('secure-delete', 1)`,
  SQLite 3.42 and later). The option is stored in `<t>_config`, so it
  holds across close and reopen. With it, a `'delete'` removes the row's
  terms from the live `<t>_data` segments at once. Without it, FTS5 only
  records a tombstone, and the deleted terms stay readable in `_data`
  until a merge rewrites the segment (host check: the term bytes stayed
  after the delete). Host tests assert that a deleted term's bytes are
  absent from `_data` after a single-source delete, a cascade and an
  in-place update, each with a positive control that they were there
  before. What this does **not** remove:
  - the deleted base rows of `ocr_spans` and `ocr_user_revisions`, and
    any freed page, which keep their bytes inside the encrypted file until
    SQLite reuses the space, because `PRAGMA secure_delete` is off. The
    pinned build has no `SQLITE_SECURE_DELETE` compile option and the app
    sets only `PRAGMA synchronous = FULL` at open. This is unchanged from
    every other deleted row in the vault; turning the pragma on is a
    follow-up for the vault as a whole, not for search.
  - a superseded correction. It is still a row of `ocr_user_revisions`
    (history), so it stays indexed until its source is deleted; search
    filters it out.
- **Fail closed and re-checked at every open (review).** `SearchIndex.create`
  first probes, on a temporary table, for FTS5 and for the secure-delete
  option. If the probe fails, or any index statement fails, it drops every
  index table and trigger and returns false: there is never an index
  without secure-delete. `SearchIndex.callback` (an `onOpen` callback
  added in `OpenLifeDatabaseFactory`) runs `SearchIndex.ensure` at every
  open. A complete index (both tables, all six triggers, secure-delete on)
  is left alone, with reads only. Anything else is recreated in one
  transaction, which also re-indexes existing rows. So a transient
  failure costs one session its index, not the vault forever. The cause
  of a failure is never logged or kept, because SQLite messages can quote
  SQL.
- **Engine floor 3.46.1 (PR #36 finding, 2026-11-26).**
  - SQLite 3.42.0 through 3.46.0 write a corrupt FTS5 external-content index
    when a row is removed with the `'delete'` command while secure-delete
    is on.
    - Host reproduction, observed on `org.xerial:sqlite-jdbc` builds of
      each SQLite version (exact SQL in
      `docs/verification/P2-08-local-search.md`): create the table,
      set secure-delete, insert one row through the insert trigger and
      remove it through the delete trigger, then run `'integrity-check'`.
      It reports `SQLITE_CORRUPT_VTAB` on 3.42.0, 3.43.2, 3.44.1, 3.45.1,
      3.45.3 and 3.46.0, and passes on 3.46.1, 3.47.0, 3.49.1, 3.50.3 and
      3.53.3.
    - An index written by 3.45.1 or 3.46.0 also fails the check on 3.46.1
      and 3.53.3, so the file itself is corrupt, not just reported as
      corrupt. An index written by 3.46.1 or 3.53.3 passes the check on
      3.45.1.
    - Queries still returned correct hits, so the corruption is silent
      without the check.
    - A regular (internal-content) FTS5 table with secure-delete passes on
      every version tested.
    - The `'delete'` values are exactly the indexed ones, and the same
      statements are clean from 3.46.1 on, so this is an engine defect,
      not our SQL. No release note or check-in was available offline to
      cite.
  - `SearchIndex` therefore creates the index, and keeps it at open, only
    when `sqlite_version()` is at least `MIN_SQLITE_VERSION` = 3.46.1.
    Otherwise, or when the version cannot be read, no index exists and
    search uses the LIKE fallback.
  - The pinned SQLCipher 4.19.0 bundles SQLite 3.53.4 (static read of the
    AAR, not observed on a device), and it has been the only SQLCipher
    pinned since the first commit. No install has therefore written this
    index on an affected engine, and no rebuild is needed. A vault opened
    on an affected engine would lose its index at open (fail closed)
    rather than keep it.
- **No VACUUM.** External-content tables are keyed on the content tables'
  implicit rowid, which VACUUM may renumber. OpenLife never runs VACUUM,
  and a boundary test enforces this. Any future VACUUM must be followed by
  `INSERT INTO <t>(<t>) VALUES('rebuild')` for both tables.

### Query

- `SearchRepository.search(query, limit = 50): Flow<List<SearchHit>>`.
  `SearchHit` carries `sourceId`, `revisionId`, `spanId?`, `snippet`,
  `isCorrection` and `highlights`.
- **Sanitising.** Input is cut into tokens made of the characters the
  unicode61 tokenizer keeps (letters, numbers of every kind such as `Ⅻ`,
  `x²` and `½`, and private-use characters), from at most 200 characters. A query keeps at most 8 tokens of at most 64
  code points each. Each token becomes a quoted FTS5 string with `"`
  doubled, and the last token becomes a prefix (`"tok"*`). Tokens are
  joined by implicit AND. The expression is a bound argument. FTS5
  operators typed by the user (`OR`, `NOT`, `NEAR(`, `*`, `^`, `col:`) are
  therefore literal words or dropped punctuation.
- **Ranking.** Results are ranked by `bm25()` across both tables
  (`UNION ALL`), with ties broken by source, extracted before corrected,
  and span order.
- **What is searchable: only what the viewer shows.**
  - The source must be `READY`.
  - The revision must be the source's latest, ordered by `startedAt` then
    `rowid` (OcrViewDao's order). It must also be `READY`, so never
    `STALE`, and its review state must not be `REJECTED`.
  - A span whose latest review is `REJECTED` is excluded.
  - Only the latest correction of a span counts.

  This is wider than the brief's wording ("exclude rejected revisions,
  STALE revisions and non-READY sources"). Rejected spans and superseded
  runs are also excluded, so that a hit always opens on text the viewer
  shows and can select.
- **Snippets and highlights** are built in Kotlin (`SearchSnippet`) from the
  stored text, not with FTS5 `snippet()`, so the FTS and LIKE paths look
  the same. Matching folds case and diacritics, as the tokenizer does. A
  snippet is at most 160 characters, plus ellipses.

### Tokenizer limits and the substring path (review W-02)

unicode61 splits words at spaces, punctuation and (for Indic scripts) at
vowel signs. Host checks showed:
- Chinese and Japanese runs are one token, so a word inside a run (`請求書`
  in `東京都の請求書`) is not found by the index. Thai, Lao, Khmer,
  Myanmar and Tibetan are written without spaces and have the same problem.
- Devanagari and other Indic words are split at their vowel signs, so the
  index over-matches and cannot match a whole word as typed.
- Arabic harakat are not removed by `remove_diacritics`, so unvocalised
  input misses vocalised text (and the reverse). Not handled; disclosed.
- `Ⅻ`, `x²` and `½` are kept as tokens; the sanitiser now keeps them too.

Input that contains a script without word spacing, or a combining mark of a
script outside Latin, Greek and Cyrillic (`SearchQuery.needsSubstringSearch`),
is searched with the LIKE path below even when the index exists. Its terms
keep their combining marks (`SearchQuery.substringTerms`), and the snippet
highlights substrings rather than words. The LIKE path is unranked. A
trigram index (or unicode61 `categories`) would serve these scripts with
ranking; that is a follow-up in the step notes.

### Fallback (R5)

When the index is absent (no FTS5, no secure-delete, or a failed create),
the repository runs a `lower(column) LIKE ? ESCAPE '\'` query that has the
same API and the same filters. The pattern is `%term%` with `% _ \`
escaped. This fallback is unranked and ordered by source. The pattern is
lowercased exactly as SQLite's built-in `lower()` lowercases the column,
ASCII `A`-`Z` only (review W-05), so text typed exactly as stored is always
found (`Ärzte` finds `Ärzte`); `ärzte` does not find `Ärzte`, because
neither side folds non-ASCII case (no ICU in the pinned build).

**Deviation recorded.** The brief asks for "a normalised lowercase
column". No column was added. Such a column would sit inside SQLCipher, so
it would not breach the no-plaintext-index rule. But it would change two
entities and the schema's identityHash, and add write paths, all for a
code path that the pinned engine never takes (see the evidence above).
Performance is recorded on the host engine for both paths:
`fiveHundredSpansOnTheLikeFallback` times the fallback, and
`fiveHundredSpansOnTheHostEngine` times FTS5. SQLite's
`lower()` folds ASCII only, so the fallback does not fold diacritics or
non-Latin case. A database without the index gains it at the next open on
an engine that supports it (`SearchIndex.ensure`).

### UI (R4, R7, R9)

- A search icon in the list's top bar opens `Routes.Search`. It is always
  shown; in an empty vault the search simply finds nothing. The query is never a route argument.
- The field is `ContentTextField`: no Copy/Cut, no autocorrect, no keyboard
  learning (A-02). It does not autocomplete and keeps no history.
- Results update as the user types (200 ms debounce, latest query only).
  Each row shows "Extracted text" or "Corrected by you", the snippet in the
  data face with matches on the evidence highlighter and underlined (never
  colour alone), and the item's generic import label. Rows are at least
  48 dp.
- Tapping a result opens the viewer with that span selected. The viewer's
  existing selection rules still apply.
- The query and results live in an activity-scoped `SearchViewModel`, in
  memory only: no `SavedStateHandle` and no saved instance state. They are
  cleared in `MainActivity.onStop`, on memory trim, and before a vault
  reset. Queries and results are never logged, and errors are shown as "no
  results" without the message, because SQLite errors can echo the query.

## Testing approach

- The host JVM has no SQLCipher and no Android SQLite. The vault JVM tests
  therefore run the real migration, triggers and query SQL through
  `HostSqlite` on a pinned engine: `org.xerial:sqlite-jdbc` 3.53.4.0, a
  vault test-only dependency whose SQLite (3.53.4) is the one inside
  SQLCipher 4.19.0 (ADR-0017). The engine is the same locally and in CI.
  Until 2026-11-26 the engine was the host's python `sqlite3` module, so
  the result varied by machine.
  - The bridge enables `foreign_keys` (Room turns it on) and, by
    default, `recursive_triggers`. No production code sets
    `recursive_triggers`; on a device it has the build default. The
    pinned `libsqlcipher.so` lists `DEFAULT_RECURSIVE_TRIGGERS` among its
    compile-option strings (a static read of the AAR, not observed on a
    device), and the P3-02 suites whose result could depend on it run
    with it on and off (P3-02 review M-05; corrected 2026-11-26). Cascades fire the index's delete triggers with
    `recursive_triggers` on or off (host check); the setting is not what
    makes deletion reach the index.
  - The index needs SQLite 3.46.1 or later (see "Engine floor"). On the
    pinned engine a missing index is a test failure, so no index test
    skips (`PinnedEngineTest` checks the version and the index). Only an
    engine chosen with `OPENLIFE_HOST_SQLITE` (`python3`, or
    `scripts/host-sqlite/LineHost.java` over another sqlite-jdbc jar) may
    skip them, with a message naming the version. The fail-closed tests
    and `SearchIndexEngineGateTest` (which reports a chosen version
    through `sqlite_version()`) run on any version.
  - Every mutation sequence in `SearchIndexHostTest` ends with FTS5
    `'integrity-check'`, both plain and with rank 1.
    `everyTriggerPathLeavesAnIndexThatPassesIntegrityCheck` runs it after
    each trigger path (insert, narrowed update, same-text update,
    column-only update, delete, cascade) on spans and corrections, on v5,
    fresh v6 and migrated v6 databases.
  - This proves the SQL and the trigger behaviour, but not SQLCipher.
  - The same cases are written for SQLCipher in `SearchRepositoryTest`
    (instrumented, device gap).
- Measured latency is **host only** (500 spans, 50 hits, on the
  development VM). The figures are in
  `docs/verification/P2-08-local-search.md`. The acceptance target (under 200 ms for 500 spans
  on a device) is unverified until the instrumented run.

## Consequences

- C3 facts (P3-07) can add their own external-content table and triggers
  in the same way.
- P1-20 (navigation restructuring) must carry over `Routes.Search`,
  `Routes.Viewer.spanId`, the list-detail `selectedSpanId`, the
  pop-to-List behaviour when a result opens at expanded width, and
  `searchViewModel.clear()` in `onStop`.
