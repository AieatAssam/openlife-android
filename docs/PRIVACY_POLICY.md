# OpenLife privacy policy

Last updated: 2026-09-24

OpenLife is designed to keep the information you deliberately import on your
Android device. This policy describes the current local-only implementation;
the repository warning still applies because the application is work in
progress and not ready for real use.

## What OpenLife stores

When you confirm an import, OpenLife stores the original supported image bytes
in its private vault, together with metadata needed to reopen and validate the
item: an app-generated identifier, format, byte count, digest, dimensions,
orientation, import time, state, and the encrypted per-file key material.
Where text extraction is enabled, its bounded result, evidence coordinates,
engine/model identifier, review state, and explicitly attributable user
corrections are stored beside the source. A failed or cancelled operation may
leave recoverable staging state until the next recovery pass.

The vault is under Android's private `noBackupFilesDir`. The database and
artefacts are encrypted, but this policy does not promise protection from a
compromised operating system, an authorised device service, or someone who
already has the unlocked device.

## App lock

You can turn on an optional app lock in onboarding or Settings. OpenLife then
asks for your fingerprint, face or screen lock, through Android's own
prompt, before it shows anything. It is an access gate against someone using
your unlocked phone. It is not extra encryption: your items are encrypted the
same way whether it is on or off. OpenLife never sees or stores your
fingerprint, face data or screen-lock credential. It only receives
"verified" or "not verified" from Android. The lock setting itself is stored
on the device, and resetting the vault turns it off.

## What leaves the device

OpenLife has no account, sync, analytics, advertising, telemetry, remote model,
or outbound sharing service. It does not transmit the vault or send imported
content to another app. The application requests no broad photo, storage,
contacts, SMS, notification-listener, accessibility-service, camera, or
network permission.

The Android Photo Picker may show items supplied by a cloud media provider. If
you select one, that provider may perform its own download, processing, or
logging outside OpenLife's control. OpenLife receives only the selected URI
through the Android intake boundary and does not add a durable grant or sync
the item.

## Backup, deletion, and retention

There is no OpenLife backup or restore service. The vault is excluded from the
configured backup and transfer paths, and uninstalling the app, losing the
device, or losing its keys can permanently lose the vault. Device-transfer
behaviour varies by manufacturer and remains a verification consideration.

Deleting an item removes its database row and app-owned artefacts after the
cleanup transaction succeeds. This is app-level deletion, not a guarantee of
forensic erasure from every storage medium or operating-system backup.

## Contact

For a privacy question, open a repository issue without personal data. For a
sensitive privacy or security report, use the repository's private GitHub
Security Advisory channel. The canonical public policy URL and maintainer
contact are chosen by the owner as part of the release work in P5-01.
