# OpenLife

OpenLife is a private, on-device memory for information a person deliberately
imports: a screenshot, receipt, letter, or similar item. It preserves the
original, shows what it understood, connects every extracted conclusion back
to evidence, and helps the user remember an explicitly confirmed obligation or
event. There is no cloud processing, no accounts, no sync, and no background
access to photos, storage, contacts, or messages.

The full product and engineering specification is `openlife-design-v0.2.md` at
the repository root. It is the source of truth; everything else in `docs/`
restates parts of it for traceability during implementation.

**Current state: Capability 0 (Trusted Core) only.** Import one JPEG or PNG,
preview the exact snapshot being retained, save it encrypted, reopen it after
process death and reboot, and delete it. No OCR, facts, records, reminders, or
export exist yet — see `docs/capabilities/C0.md` and the capability sequence
in the design document §5.

## Repository layout

```
app/     Android entry points and UI
vault/   Storage, encryption, and the import lifecycle
docs/    Principles, threat model, per-capability specs, decisions, verification evidence
```

See `AGENTS.md` for the rules that govern changes to this repository.

## Building

```bash
./gradlew assembleDebug
./gradlew :vault:test :app:test
./gradlew connectedDebugAndroidTest   # requires a running emulator or device
```

## Licence

Apache License 2.0 — see `LICENSE`. Choosing a licence for the development
repository is not itself the "choose a licence before public distribution"
decision the design document requires (§14); that remains an owner decision at
release time.
