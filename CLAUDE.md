# CLAUDE.md

Working procedure for **Claude Sonnet 5** sessions in this repository, plus
notes about *this sandbox*. Product and engineering rules live in `AGENTS.md`
and `openlife-design-v0.3.md` — read those first; they govern on conflict.

## Session start

1. Read `AGENTS.md` (rules + TDD procedure), `docs/PRODUCT_PRINCIPLES.md`,
   `docs/THREAT_MODEL.md`.
2. Run `scripts/plan-status.sh`. Take one runnable step from
   `plan/plan.yaml`; open `plan/steps/<id>.yaml` and read it completely,
   including `tdd.red`, `verification.commands`, and `docs_to_update`.
3. Claim the step (`status: in_progress`, `started`) and branch
   `plan/<step-id>-<slug>`.
4. For UI steps read `docs/design/VISUAL_IDENTITY.md` and invoke the
   project skills `material-3-expressive`, `mobile-android-design`, and
   `color-system` (installed under `.agents/skills/`, mirrored in
   `.claude/skills/`) before designing or reviewing a screen.

Work one step at a time. Never start a step whose dependencies are not
`done`. Never implement beyond the step's requirements; put ideas in the
step's `notes` instead.

## TDD loop (non-negotiable)

RED (write and run the named failing tests; commit) → GREEN (smallest
correct change; commit) → REFACTOR (re-run; commit) → VERIFY (run every
verification command; record real output in the step's `evidence`) →
DOCUMENT (`docs_to_update`) → `status: review`. Commits on the step branch at
each phase are expected and need no permission; pushing, merging to `main`,
tagging, and force-push require the owner.

Instrumented RED: a missing symbol breaks the whole test APK, so for
`androidTest` add the minimal stub signature (throwing `TODO()`) first, then
make the assertion fail. Run one class with
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`.
Boundary tests that already pass prove RED by a recorded mutation (add the
forbidden thing, watch it fail, revert; name the mutation in `evidence`).

Before claiming anything passed, re-read the actual test output. If the
emulator cannot run here, record the gap in `notes` and in
`docs/verification/`; CI's API 29/36 legs (P0-04) provide the device
evidence. Never write "assumed".

Claude-specific reminders:
- Say in one line what you are about to do, then do it; close with a short
  recap: what ran, what passed, what did not, what remains unverified.
- Use the dedicated Read/Edit/Grep tools for code; use Bash for Gradle,
  adb, and scripts.
- Do not spawn subagents for a single step. Specification steps (P3-01,
  P4-01, P5-02, P6-01, P7-01, P7-02, P7-03, P8-01, P8-02) require an
  adversarial review in their acceptance criteria; spawn one reviewer then
  and record its findings in the step's `notes`.
- Prefer state-based awaits over sleeps in tests; the shared
  `MutationQueue` and `OpenLifeApp` singleton are per-process, so every
  test that triggers fire-and-forget work must await its terminal state.

## Verification commands

```bash
scripts/plan-check.sh
./gradlew detekt lint :app:test :vault:test assembleDebug     # every change (detekt/lint enforced from P0-03)
./gradlew :vault:test :app:test                                # fast loop
./gradlew connectedDebugAndroidTest                            # emulator must be booted first (below)
bash scripts/release-smoke.sh app/build/outputs/apk/release/*.apk   # from P0-06 on
```

## Emulator: known-good launch command

```bash
./gradlew --stop; pkill -f KotlinCompileDaemon || true
emulator -avd dev36 -no-window -no-audio -no-boot-anim \
  -gpu host -feature -Vulkan \
  -no-snapshot -no-metrics -crash-report-mode disabled
```

Boots in ~30-40s with real hardware GLES acceleration.

**Do not drop `-feature -Vulkan`.** `-gpu host` alone segfaults the emulator
process outright (confirmed via exit code 139, i.e. SIGSEGV — not an OOM
kill, not a permission issue, not the sandbox: cgroup memory, coredumps, and
kill signals were all checked and ruled out). Root cause is a gfxstream↔RADV
(AMD Mesa Vulkan) driver interaction bug on this host's AMD Vega iGPU.
`-feature -Vulkan` disables only the guest Vulkan path; GLES stays
host-accelerated and stable. If a future host/driver update changes this,
re-verify by capturing the emulator's actual exit code (wrap the launch in a
script that runs the emulator in the foreground and writes `$?` to a file —
don't infer crashes from log tailing alone, which was unreliable here) before
concluding the segfault is fixed.

Fallback if GPU acceleration ever regresses again: `-gpu swiftshader_indirect`
(software rendering) — slower (single-digit minutes to boot cold) but has
worked reliably in this sandbox.

## This sandbox is resource-constrained and shared

- Only 4 CPU cores, shared with other concurrent Claude Code sessions on the
  same host (confirmed once by a stray logcat file from an unrelated
  package, `net.aieat.netswissknife`, appearing in this project's own
  instrumented-test output — a different session's `connectedAndroidTest`
  ran against the same emulator instance at the same time).
- Before booting the emulator, stop competing local daemons to free a core:
  `./gradlew --stop` and kill any lingering
  `org.jetbrains.kotlin.daemon.KotlinCompileDaemon` process. This alone was
  the difference between ~10 failed boot attempts and a clean boot, back
  when the emulator was still running on software rendering.
- `system_server` inside the guest has been observed to restart itself
  mid-session without the emulator going down. If a `connectedAndroidTest`
  run fails with `DeadSystemException` or "Can't find service: package" and
  the failure isn't shaped like a code problem, just re-run it before
  assuming the code is at fault.
- `adb shell pm clear org.openlife` has behaved like a full uninstall here;
  re-run `./gradlew :app:installDebug` afterwards.
- `adb screencap` is black by design (FLAG_SECURE); use `uiautomator dump`
  for on-device UI evidence.
- No physical Android device is available. No API 29 (the app's declared
  `minSdk`) system image can be installed — `$ANDROID_HOME` is a read-only
  Nix store path. API 29 evidence comes from the CI leg added in P0-04;
  physical-device runbooks are owner actions (see `plan/plan.yaml`
  `owner_actions_required`).
- No `perl`, no PyYAML; `python3` (stdlib only), `node`, JDK 21, `readelf`
  and `gh` exist (GitHub auth for `gh` is not guaranteed). YAML validation
  uses the SnakeYAML jar inside the Gradle wrapper (`scripts/plan-check.sh`).
- `aapt2`, `apksigner`, `zipalign` are not on PATH; use
  `$ANDROID_HOME/build-tools/37.0.0/<tool>`. No NDK is installed, so native
  builds (P8-01) are CI-only.

Full per-capability test results and the reasoning behind every environment
workaround above are in `docs/verification/` — update them, don't just fix
things quietly, when you hit and resolve something new here.
