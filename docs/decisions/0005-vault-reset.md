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

## Verification

Implementation evidence is recorded in
[`verification/P1-06-vault-reset.md`](../verification/P1-06-vault-reset.md).
