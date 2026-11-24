# ADR-0005: Explicit whole-vault reset

Date: 2026-09-23
Status: accepted for P1-06

## Context

Design §10 prohibits destructive recovery on a vault error path and allows a
separate, explicit reset for confirmed loss. The C0 vault previously exposed
an unavailable string with no retry or safe recovery route. A reset must be
recoverable if the process stops halfway through deleting app-owned files.

## Decision

- Bootstrap failures use a typed `VaultUnavailableCause`; exception messages
  never enter UI, logs, or saved diagnostics. Temporary Keystore, database
  open, and storage failures can be retried. Corrupt or missing keys and
  failed unwrap are not retryable. `RESET_INCOMPLETE` directs the user to
  finish the interrupted operation.
- Reset is offered in Settings and requires typing the localised word `RESET`
  followed by a second explicit confirmation. The unavailable screen explains
  the cause and links to Settings; it has no direct reset action.
- Reset uses the shared mutation queue and writes plus fsyncs
  `noBackupFilesDir/vault/RESET_IN_PROGRESS` before closing Room or deleting
  files. It then removes recognised UUID artefacts, the database and sidecars,
  the wrapped database key file, and the Keystore alias. The marker is removed
  last. If any required file operation fails, the marker remains and bootstrap
  reports `RESET_INCOMPLETE` until a later reset attempt finishes.
- The reset scope is the known app-owned vault files under
  `noBackupFilesDir/vault` and the `app_lock` preferences file. It retains the
  first-run acknowledgement and never touches unrelated files. In C0 there
  are no scheduled reminders; P4-04 must extend reset to cancel its alarms and
  notifications.
- A Keystore alias deletion failure is non-fatal after database and wrapped
  key deletion. A fresh database secret is generated only after the reset
  marker is absent and no key file or database remains.
- Failure diagnostics store only per-cause counts. They contain no exception
  messages, identifiers, paths, hashes, or content.

## Consequences

Reset removes the vault's live app-owned files and invalidates access through
OpenLife. It is not forensic erasure and cannot erase prior copies or plaintext
captured by a compromised operating system. Database corruption can make all
sources unrecoverable: per-source DEKs are stored only in that encrypted
database, with no separate key file or backup by design.

## Addendum (P1-18, 2026-11-24)

- **DATABASE_MISSING.** If `database.key` exists, the database file is
  absent and `artefacts/` has at least one entry, bootstrap returns
  `Unavailable(DATABASE_MISSING)` before reading the key. Room is not opened,
  recovery does not run, and no file is deleted. The screen explains that
  saved originals were kept and offers Retry; Reset vault stays the
  secondary, two-step-confirmed action defined above. Nothing is inferred
  from an unreadable or missing database (design §11).
- **Torn key file.** `database.key` is written as `database.key.tmp`,
  fsynced, renamed over the key, and the directory is fsynced. A leftover
  `.tmp` is removed at the next bootstrap. A key file that fails to decode
  is treated as a fresh install **only** when there is no database and
  `artefacts/` is empty; otherwise it stays `KEY_FILE_CORRUPT`.
- **Reset scope.** Reset removes every entry under the vault directory
  (including non-UUID strays and `-journal`), keeping only the marker until
  last, so a stray file can no longer leave the marker set permanently.
- **Saved originals without a usable database** (review R-05, R-08). No
  database file, or a 0-byte one, next to a non-empty `artefacts/` is
  `DATABASE_MISSING` whether or not the key file exists; nothing is
  deleted. As a second layer, recovery never removes unreferenced `.blob`
  files when the database has zero Source rows; it reports them
  (`keptBlobsWithoutRows`) and the app shows `DATABASE_MISSING` instead of
  an empty vault. This resolves the earlier open question.

## Verification

Implementation evidence is recorded in
[`verification/P1-06-vault-reset.md`](../verification/P1-06-vault-reset.md).
P1-18 changes: [`verification/P1-18-vault-remediation.md`](../verification/P1-18-vault-remediation.md).
