# OpenLife

![status](https://img.shields.io/badge/status-work--in--progress-orange?style=for-the-badge)

> [!WARNING]
> ## 🚧 Work in progress — not ready for real use 🚧
>
> OpenLife is being built one small capability at a time, and right now it
> only knows how to hold a single test image. It's not feature-complete, it
> hasn't been through a real security audit, and it *will* change shape
> under you. Don't install it expecting to keep anything in it yet.

OpenLife is a private, on-device memory app for the stuff you choose to save
— a screenshot, a receipt, a letter, whatever. It keeps your original file
exactly as you gave it to it, shows you exactly what it saved, and links
anything it later figures out back to the evidence that supports it. Nothing
leaves your phone: no cloud processing, no accounts, no sync, no background
access to your photos, storage, contacts, or messages.

The full product and engineering spec lives in `openlife-design-v0.2.md` at
the repo root — that's the source of truth. Everything under `docs/` is a
working restatement of pieces of it, kept up to date as the app is built.

## Where things stand

Only **Capability 0 ("Trusted Core")** exists so far: import one JPEG or PNG,
preview the exact snapshot before saving, save it encrypted, reopen it after
the app is killed or the phone reboots, and delete it. That's the whole
feature set today — no OCR, no extracted facts, no reminders, no export.
`docs/capabilities/C0.md` spells out exactly what's in and out of scope, and
§5 of the design doc lays out what's planned after this.

## Repository layout

```
app/     Android entry points and UI
vault/   Storage, encryption, and the import lifecycle
docs/    Principles, threat model, per-capability specs, decisions, verification evidence
```

Ground rules for contributing changes are in `AGENTS.md`.

## Building

```bash
./gradlew assembleDebug
./gradlew :vault:test :app:test
./gradlew connectedDebugAndroidTest   # needs a running emulator or device
```

## Licence

Apache License 2.0 — see `LICENSE`. That covers the source code here; it
isn't the same as deciding the app is ready for anyone to install and trust,
which is a separate call to make later (design doc §14).
