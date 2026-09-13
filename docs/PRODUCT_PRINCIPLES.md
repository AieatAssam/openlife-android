# OpenLife product principles

Source of truth: `openlife-design-v0.2.md` at the repository root (design document,
version 0.2, 13 September 2026). This file restates the non-negotiable principles
from Section 2 of that document so they are easy to reference from code review and
`AGENTS.md`. If this file and the design document ever disagree, the design
document governs; fix this file.

## Mission

OpenLife is a private, on-device memory for important information a person
deliberately gives it. The central interaction is: import one item → inspect its
evidence → confirm useful information → find it or receive a local reminder. The
source remains accessible from every downstream conclusion.

## P1 — Local processing and storage

User content, extracted information, derived records, indexes and diagnostics
MUST remain within OpenLife on the device. The app MUST NOT declare the
`INTERNET` permission, include remote processing, or delegate transmission to
another app. No analytics, telemetry, advertising or remote crash reporting SDK
is permitted. `INTERNET` is a socket permission, not a complete defence against
every form of data export — backup, export intents and outbound handoffs need
their own controls (see `THREAT_MODEL.md`).

## P2 — Explicit narrow intake

Only a user-selected item may be imported, via an incoming share grant or the
system Photo Picker. Do not request broad photo, storage, contacts, SMS, email,
notification-listener, accessibility-service or all-files access. A runtime
permission must have a feature-specific reason, be requested at the point of
use, and have a functional denial path.

## P3 — Provenance before intelligence

No extracted or derived fact may appear as an established record field without a
navigable evidence chain. A user assertion is allowed only when labelled as
user-provided, with its own provenance. Missing information stays missing.
Evidence quality and user confirmation are separate from truth.

## P4 — User control over consequences

Extraction may propose; the user confirms records and reminders. Imports cannot
trigger purchases, payments, calls, replies, browser navigation or calendar
writes. All external sharing and export are outside this specification because
the recipient app may transmit content.

## P5 — Small complete capabilities

Each capability must be fully specified, implemented, tested, security-reviewed
and provenance-complete before the next begins. A functioning happy path, a
checklist with no evidence, or a model demonstration is not completion. This
repository currently implements Capability 0 only; see
`docs/capabilities/C0.md`.

## P6 — Open source and auditability

Publish source, build instructions, permission rationale, dependency inventory,
licences, threat model and test evidence for a release. Pin dependencies and
verify resolved artefacts. Document the relationship between published source
and distributed binaries; claim reproducibility only after independent rebuild
comparison. Do not represent a self-review as an independent audit.

## P7 — Honest limits and recoverability

Explain irreversible loss and unsupported inputs before they cause harm. No
cloud backup or device-transfer backup is allowed. Losing the device,
uninstalling the app, or losing its keys can permanently lose the vault. This is
a deliberate cost of the strict local-only scope, not a recovery feature to
quietly add later.

## Scope boundaries

OpenLife is not a general chatbot, autonomous agent, cloud knowledge service,
email client or background phone monitor. It does not crawl storage, read
inboxes, intercept notifications, scan the clipboard or import an address book.
There are no accounts, sync, remote processing, advertising or automatic
external actions. Home management and family/shared-household features are
excluded from the product entirely.

## Requirement language

MUST and MUST NOT are acceptance requirements. SHOULD permits a documented,
reviewed exception that preserves the product constraints. An implementation
agent may refine a proposed default before building, but must record the reason
in `docs/decisions/` and repeat affected tests. A product-boundary change
requires owner approval.
