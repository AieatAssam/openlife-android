# ADR-0016: CI emulator images (ATD vs google_apis)

Status: accepted for hosted GitHub Actions instrumented legs

Date: 2026-09-19

## Context

P0-04-R3 originally required connected tests on API 29 and API 36
`google_apis` x86_64 emulators with a `pixel_7` hardware profile. That
wording matches the local `dev36` AVD used in this sandbox, not a CI
cost/time budget.

PR CI run https://github.com/AieatAssam/openlife-android/actions/runs/35444099558
did **not** hang during emulator boot. Both legs installed
`google_apis` + `pixel_7`, started the emulator, and reported
`Emulator booted` (API 29 in ~16s, API 36 in ~54s). The 35-minute job
timeout was a later hang: `android-emulator-runner` runs `script` with
`/usr/bin/sh` (dash on Ubuntu), the inline wrapper used bash-only
`set -o pipefail`, dash aborted, and the `always()` `adb logcat -d`
step blocked until SIGTERM (exit 143).

Separately, `google_apis` + GMS + Pixel 7 is heavier than needed for
C0 connected tests once they actually run. Automated Test Device (ATD)
images exist from API 30 and are the recommended CI target in
ReactiveCircus/android-emulator-runner. `ubuntu-latest` currently
floats to Ubuntu 24.04, which has known AVD path/`XDG_CONFIG_HOME`
issues; Linux + KVM is still preferred over macOS.

## Decision

- Keep the API **29 and 36** matrix (minSdk + current-target evidence).
- Pin instrumented jobs to **`ubuntu-22.04`** with KVM udev rules and
  `test -e /dev/kvm` fail-fast. Do not switch to macOS runners.
- API 29: **`google_apis` x86_64**. ATD images start at API 30; this
  package booted successfully on run 35444099558.
- API 36: **`aosp_atd` x86_64**, confirmed in Google's
  `sys-img/aosp_atd` catalog as
  `system-images;android-36;aosp_atd;x86_64`. C0 connected tests do
  not require Play Store or a Pixel skin.
- Do not pass `profile: pixel_7` in CI. Local `dev36` may keep Pixel 7.
- Cache the AVD (`~/.android/avd/*`, `~/.android/*.img`,
  `~/.android/adb*`), and run tests with `-no-snapshot`, so every run cold
  boots. The earlier `-no-snapshot-save` choice was revised on 2026-09-23,
  see Amendment below.
- Run the connected Gradle command from
  `scripts/ci/run-connected-android-tests.sh` under bash. Bound leftover
  `adb logcat` to 20 seconds.

This is a documented drift from the original P0-04-R3 image/profile
wording. The API pair, KVM, SHA-pinned emulator-runner, assertion-aware
retry, and 30-day artefacts are unchanged. P0-04 stays `review` until
app connected failures owned by P1 steps are closed.

Hosted confirmation: PR run
https://github.com/AieatAssam/openlife-android/actions/runs/35447168861
finished both legs in about seven minutes. Vault connected tests were
72/72 on API 29 and API 36. App connected tests failed on assertions
(34/38 on API 29, 35/38 on API 36), not on emulator boot or logcat hang.

## Consequences

- API 36 CI no longer exercises Google APIs / GMS. If a later step
  needs Play services on the device (unbundled ML Kit, Play-dependent
  Photo Picker behaviour), switch that leg to `google_atd` or
  `google_apis` and record it here.
- Every instrumented run cold-boots, which adds roughly half a minute per
  leg. That is the price of not trusting a cached quick-boot snapshot.
- `ubuntu-22.04` must be bumped deliberately when GitHub retires it.

## Amendment 2026-09-23: cold boot instead of the cached snapshot

- **What happened.** Run 35865849434 on `main` had a cache miss, generated
  the AVD snapshot, cold-booted and passed, and saved the cache. Every later
  API 29 run restored that snapshot: "Successfully loaded snapshot
  'default_boot' using 1380 ms". The system then reported
  `sys.boot_completed=1` with `settings` and `input` services missing ("No
  service published for: input"). The runner's first `input keyevent`
  failed, and the step then hung until the 55-minute job timeout (runs
  35881181744 and 35888111697, including a rerun).
- **What didn't cause it.** The emulator (37.1.11.0) and runner image
  versions were identical between the good and bad runs. No app code ran
  before the failure.
- **Decision.** Test steps pass `-no-snapshot` (no load, no save). The
  cached AVD still spares image and AVD creation.

