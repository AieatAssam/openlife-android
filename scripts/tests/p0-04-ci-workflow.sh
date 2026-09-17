#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/ci.yml"
GRADLE_SETUP="$ROOT/.github/actions/gradle-setup/action.yml"
RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
CI_FILES=("$WORKFLOW" "$GRADLE_SETUP" "$RELEASE_WORKFLOW")
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

require "JDK 21 is configured" 'java-version: *"?21"?'
require "all jobs use the reusable Gradle setup action" '\./\.github/actions/gradle-setup'
require "API 29 and API 36 matrix is declared" 'api-level: *\[29, *36\]'
require "KVM is enabled" 'enable-kvm: *true'
require "CI invokes plan-check" 'scripts/plan-check\.sh'
require "CI invokes the standard verification command" './gradlew detekt lint :app:test :vault:test assembleDebug'
require "connected tests have a retry wrapper" 'attempt|retry'
require "connected runner uses swiftshader without snapshots" 'no-snapshot.*no-window.*swiftshader_indirect'
require "retry classifier names assertion-shaped instrumentation failures" 'INSTRUMENTATION_RESULT: shortMsg=\((Test failed|Assertion|junit)'
require "instrumented results retain artifacts for 30 days" 'retention-days: *30'
require "filtered OpenLife logcat is uploaded" 'OpenLifeRecovery|org\.openlife'
require "release APK inspection is wired" 'scripts/inspect-release-apk\.sh'
require "push trigger is present" '^  push:'
require "pull request trigger is present" '^  pull_request:'

generic_short_msg="$(grep -E 'INSTRUMENTATION_RESULT: shortMsg=[^ (]' "$WORKFLOW" || true)"
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
