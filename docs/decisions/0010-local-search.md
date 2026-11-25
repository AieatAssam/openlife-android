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
- **No VACUUM.** External-content tables are keyed on the content tables'
  implicit rowid, which VACUUM may renumber. OpenLife never runs VACUUM,
  and a boundary test enforces this. Any future VACUUM must be followed by
  `INSERT INTO <t>(<t>) VALUES('rebuild')` for both tables.

### Query

- `SearchRepository.search(query, limit = 50): Flow<List<SearchHit>>`.
  `SearchHit` carries `sourceId`, `revisionId`, `spanId?`, `snippet`,
  `isCorrection` and `highlights`.
- **Sanitising.** Input is cut into tokens made of letters and digits, from
  at most 200 characters. A query keeps at most 8 tokens of at most 64
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

### Fallback (R5)

`SearchIndex.create` first probes for FTS5 with a temporary virtual table.
If the probe fails, it creates nothing and the vault still opens. The
repository counts the two index tables and, if they are absent, runs a
`lower(column) LIKE ? ESCAPE '\'` query that has the same API and the same
filters. The pattern is `%token%` with `% _ \` escaped. This fallback is
unranked and ordered by source.

**Deviation recorded.** The brief asks for "a normalised lowercase
column". No column was added: a stored lowercase copy would be a second
plaintext copy of the content inside the vault, and the fallback exists
only for a build that the evidence above says we do not ship. SQLite's
`lower()` folds ASCII only, so the fallback does not fold diacritics or
non-Latin case. A database that was created without FTS5 does not gain
the index later on its own. The migration has already run, so a later
step would need its own migration to rebuild it.

### UI (R4, R7, R9)

- A search icon in the list's top bar (shown when the list has items)
  opens `Routes.Search`. The query is never a route argument.
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
  therefore run the real migration, triggers and query SQL against the
  host's Python `sqlite3` module (SQLite 3.53.3 with FTS5) through
  `HostSqlite`, a stdlib-only subprocess bridge.
  - The bridge enables `foreign_keys` and `recursive_triggers`, matching
    the device build.
  - This proves the SQL and the trigger behaviour, but not SQLCipher.
  - The same cases are written for SQLCipher in `SearchRepositoryTest`
    (instrumented, device gap).
- Measured latency is **host only**: 500 spans, 50 hits, about 16 ms on
  the development VM. The acceptance target (under 200 ms for 500 spans
  on a device) is unverified until the instrumented run.

## Consequences

- C3 facts (P3-07) can add their own external-content table and triggers
  in the same way.
- P1-20 (navigation restructuring) must carry over `Routes.Search`,
  `Routes.Viewer.spanId`, the list-detail `selectedSpanId`, the
  pop-to-List behaviour when a result opens at expanded width, and
  `searchViewModel.clear()` in `onStop`.
