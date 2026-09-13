# CLAUDE.md

Session/environment notes for Claude Code working in this repository. Product
and engineering rules live in `AGENTS.md` and `openlife-design-v0.2.md` —
read those first. This file is about *this sandbox*: how to get a working
build and a stable emulator in it.

## Emulator: known-good launch command

```bash
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
- No physical Android device is available. No API 29 (the app's declared
  `minSdk`) system image can be installed — `$ANDROID_HOME` is a read-only
  Nix store path. See `docs/decisions/0001-c0-defaults.md` item 4 for how
  this gap is tracked in the C0 acceptance evidence.

## Verification commands

```bash
./gradlew assembleDebug lint          # every change, no emulator needed
./gradlew :vault:test :app:test       # every change, no emulator needed
./gradlew connectedDebugAndroidTest   # emulator must be booted first (see above)
```

Full per-capability test results and the reasoning behind every environment
workaround above are in `docs/verification/C0.md` — update it, don't just
fix things quietly, when you hit and resolve something new here.
