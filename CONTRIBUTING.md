# Contributing to OpenLife

OpenLife is developed as a small, local-only Android application. Start with
the repository rules in [AGENTS.md](AGENTS.md), the [product principles](docs/PRODUCT_PRINCIPLES.md),
and the [threat model](docs/THREAT_MODEL.md). The plan in
[`plan/plan.yaml`](plan/plan.yaml) authorises the work; do not implement an
unassigned roadmap item.

## Change procedure

Work on one plan step at a time and use its TDD brief:

1. Claim the step on a `plan/<step-id>-<slug>` branch.
2. Add every named RED test and run it so it fails for the intended reason.
3. Implement the smallest GREEN change, then commit.
4. Refactor without weakening validation, recovery, or evidence.
5. Run the step verification and the standard checks.
6. Update specifications, verification records, and traceability in the same change.

Use synthetic fixtures only. Do not log user content or source identifiers,
add network access, add telemetry, write to the clipboard, open links, or
silently weaken a validation boundary. Preserve original bytes and recoverable
state. New runtime dependencies require a decision record, an allowed licence,
and refreshed verification metadata.

## Verification

For the current repository, run:

```text
scripts/plan-check.sh
./gradlew detekt lint :app:test :vault:test assembleDebug
```

Changes touching Android lifecycle, storage, receivers, Keystore, SQLCipher,
or Compose screens also require the connected suites when a device is
available:

```text
./gradlew :vault:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Record unavailable device or CI checks as gaps; never claim a command ran when
it did not.

## Developer Certificate of Origin

Contributions must carry a DCO sign-off. Add a `Signed-off-by` trailer using
your real name and email address:

```text
git commit -s
```

By signing, you certify that you have the right to submit the work under the
repository's Apache License 2.0.
