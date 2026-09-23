#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/ci.yml"
GRADLE_SETUP="$ROOT/.github/actions/gradle-setup/action.yml"
RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
RETRY_CLASSIFIER="$ROOT/scripts/ci/connected-test-failure-is-assertion.sh"
CONNECTED_WRAPPER="$ROOT/scripts/ci/run-connected-android-tests.sh"
CI_FILES=("$WORKFLOW" "$GRADLE_SETUP" "$RELEASE_WORKFLOW" "$RETRY_CLASSIFIER")
if [[ -f "$CONNECTED_WRAPPER" ]]; then
  CI_FILES+=("$CONNECTED_WRAPPER")
fi
pass=0
fail=0

require() {
  local label="$1" pattern="$2"
  if grep -Eq "$pattern" "${CI_FILES[@]}"; then
    printf 'ok - %s\n' "$label"
    pass=$((pass + 1))
  else
    printf 'not ok - %s\nmissing pattern: %s\n' "$label" "$pattern" >&2
    fail=$((fail + 1))
  fi
}

forbid_in_workflow() {
  local label="$1" pattern="$2"
  if grep -Eq "$pattern" "$WORKFLOW"; then
    printf 'not ok - %s\nforbidden pattern: %s\n' "$label" "$pattern" >&2
    fail=$((fail + 1))
  else
    printf 'ok - %s\n' "$label"
    pass=$((pass + 1))
  fi
}

require "JDK 21 is configured" 'java-version: *"?21"?'
require "all jobs use the reusable Gradle setup action" '\./\.github/actions/gradle-setup'
require "API 29 matrix entry is declared" 'api-level: *(29|\[29)'
require "API 36 matrix entry is declared" 'api-level: *(36|\[29, *36\])'
require "KVM is enabled via udev rules" 'KERNEL=="kvm".*MODE="0666"'
require "KVM device is asserted after udev" 'test -e /dev/kvm'
if grep -A20 '^  instrumented-tests:' "$WORKFLOW" | grep -Eq 'runs-on: ubuntu-22.04'; then
  printf 'ok - instrumented job pins ubuntu-22.04 rather than ubuntu-latest\n'
  pass=$((pass + 1))
else
  printf 'not ok - instrumented job pins ubuntu-22.04 rather than ubuntu-latest\n' >&2
  fail=$((fail + 1))
fi
require "cmdline-tools setup is resilient" 'cmdline-tools/latest'
require "CI invokes plan-check" 'scripts/plan-check\.sh'
require "CI invokes the standard verification command" './gradlew detekt lint :app:test :vault:test assembleDebug'
if grep -Eq 'name: *app-debug-apk' "$WORKFLOW" &&
    grep -Fq 'app/build/outputs/apk/debug/app-debug.apk' "$WORKFLOW" &&
    grep -Eq 'if: *success\(\)' "$WORKFLOW"; then
  printf 'ok - CI build job uploads debug APK artifact on assemble success\n'
  pass=$((pass + 1))
else
  printf 'not ok - CI build job uploads debug APK artifact on assemble success\n' >&2
  fail=$((fail + 1))
fi
require "connected tests have a retry wrapper" 'attempt|retry'
require "connected runner uses swiftshader" 'gpu swiftshader_indirect'
require "test run reuses a snapshot without saving" 'no-snapshot-save'
require "AVD snapshot cache is configured" 'id: avd-cache'
require "AVD cache includes ~/.android/avd" '~/\.android/avd'
require "workflow uses the testable assertion classifier" 'scripts/ci/connected-test-failure-is-assertion\.sh'
require "connected tests run under bash not dash" 'bash scripts/ci/run-connected-android-tests\.sh'
require "API 36 uses aosp_atd" 'target: aosp_atd'
require "API 29 keeps google_apis" 'target: google_apis'
require "emulator cores are capped" 'cores: *"?2"?'
require "emulator ram-size is modest" 'ram-size: *2048M'
require "force-avd-creation is false for snapshot reuse" 'force-avd-creation: *false'
require "logcat capture is time-bounded" 'timeout 20s adb logcat'
forbid_in_workflow "pixel_7 profile is not used on CI emulators" 'profile:[[:space:]]*pixel_7'
forbid_in_workflow "workflow does not pass bash-only pipefail to emulator-runner sh" 'set -o pipefail'
if [[ -x "$RETRY_CLASSIFIER" ]]; then
  classifier_tmp="$(mktemp -d)"
  trap 'rm -rf "$classifier_tmp"' EXIT
  printf '%s\n' 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.' >"$classifier_tmp/infrastructure.log"
  printf '%s\n' 'INSTRUMENTATION_RESULT: shortMsg=Test failed: expected 1 but was 2' >"$classifier_tmp/assertion.log"
  printf '%s\n' 'INSTRUMENTATION_RESULT: shortMsg=(Test failed: expected 1 but was 2)' >"$classifier_tmp/assertion-paren.log"
  if "$RETRY_CLASSIFIER" "$classifier_tmp/infrastructure.log" ||
      ! "$RETRY_CLASSIFIER" "$classifier_tmp/assertion.log" ||
      ! "$RETRY_CLASSIFIER" "$classifier_tmp/assertion-paren.log"; then
    printf 'not ok - assertion classifier distinguishes infrastructure and assertion output\n' >&2
    fail=$((fail + 1))
  else
    printf 'ok - assertion classifier distinguishes infrastructure and assertion output\n'
    pass=$((pass + 1))
  fi
else
  printf 'not ok - assertion classifier distinguishes infrastructure and assertion output\nmissing: %s\n' "$RETRY_CLASSIFIER" >&2
  fail=$((fail + 1))
fi
if grep -Fq 'INSTRUMENTATION_RESULT: shortMsg=[(]?' "$RETRY_CLASSIFIER"; then
  printf 'ok - retry classifier names assertion-shaped instrumentation failures\n'
  pass=$((pass + 1))
else
  printf 'not ok - retry classifier names assertion-shaped instrumentation failures\n' >&2
  fail=$((fail + 1))
fi
if [[ -x "$CONNECTED_WRAPPER" ]] && grep -Eq 'PIPESTATUS|attempt' "$CONNECTED_WRAPPER"; then
  printf 'ok - bash connected-test wrapper exists and retries under bash\n'
  pass=$((pass + 1))
else
  printf 'not ok - bash connected-test wrapper exists and retries under bash\nmissing: %s\n' "$CONNECTED_WRAPPER" >&2
  fail=$((fail + 1))
fi
require "instrumented results retain artifacts for 30 days" 'retention-days: *30'
require "filtered OpenLife logcat is uploaded" 'OpenLifeRecovery|org\.openlife'
require "release APK inspection is wired" 'scripts/inspect-release-apk\.sh'
require "push trigger is present" '^  push:'
require "pull request trigger is present" '^  pull_request:'
require "emulator-runner pin is the ubuntu-24-safe v2.38.0 SHA" 'android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d'

if grep -Eq '^[[:space:]]*enable-kvm:' "$WORKFLOW"; then
  printf 'not ok - workflow does not pass enable-kvm to the pinned emulator-runner\n' >&2
  fail=$((fail + 1))
else
  printf 'ok - workflow does not pass enable-kvm to the pinned emulator-runner\n'
  pass=$((pass + 1))
fi

generic_short_msg="$(grep -F "INSTRUMENTATION_RESULT: shortMsg='" "$WORKFLOW" || true)"
if [[ -z "$generic_short_msg" ]]; then
  printf 'ok - retry classifier does not treat every instrumentation shortMsg as an assertion\n'
  pass=$((pass + 1))
else
  printf 'not ok - retry classifier does not treat every instrumentation shortMsg as an assertion\n%s\n' "$generic_short_msg" >&2
  fail=$((fail + 1))
fi

bad_refs="$(grep -hE '^ *- uses: .+@' "${CI_FILES[@]}" | grep -Ev '@[0-9a-f]{40}$' || true)"
if [[ -z "$bad_refs" ]]; then
  printf 'ok - every third-party action is pinned by full commit SHA\n'
  pass=$((pass + 1))
else
  printf 'not ok - every third-party action is pinned by full commit SHA\n%s\n' "$bad_refs" >&2
  fail=$((fail + 1))
fi

printf '%s passed, %s failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
