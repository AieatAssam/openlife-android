#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

assert_contains() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == *"$expected"* ]]; then
    printf 'ok - %s\n' "$label"
    pass=$((pass + 1))
  else
    printf 'not ok - %s\nexpected to find: %s\nactual:\n%s\n' "$label" "$expected" "$actual" >&2
    fail=$((fail + 1))
  fi
}

assert_nonzero() {
  local label="$1" output_file="$2" status="$3"
  if [[ "$status" -ne 0 ]]; then
    printf 'ok - %s\n' "$label"
    pass=$((pass + 1))
  else
    printf 'not ok - %s\nexpected non-zero exit\nactual:\n%s\n' "$label" "$(<"$output_file")" >&2
    fail=$((fail + 1))
  fi
}

cp -R "$ROOT/plan" "$TMP/plan-missing"
sed -i 's#file: steps/P0-01.yaml#file: steps/NOPE.yaml#' "$TMP/plan-missing/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing" >"$TMP/missing.out" 2>&1
missing_status=$?
set -e
assert_nonzero "plan-check rejects a missing step file" "$TMP/missing.out" "$missing_status"
assert_contains "missing-step diagnostic names file" "NOPE.yaml" "$(<"$TMP/missing.out")"

cp -R "$ROOT/plan" "$TMP/plan-cycle"
sed -i 's#depends_on: \[\], estimate: S#depends_on: [P0-02], estimate: S#' "$TMP/plan-cycle/plan.yaml"
sed -i 's#depends_on: \[P0-01\], estimate: M#depends_on: [P0-01], estimate: M#' "$TMP/plan-cycle/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-cycle" >"$TMP/cycle.out" 2>&1
cycle_status=$?
set -e
assert_nonzero "plan-check rejects a dependency cycle" "$TMP/cycle.out" "$cycle_status"
assert_contains "cycle diagnostic is present" "cycle" "$(<"$TMP/cycle.out")"

bash "$ROOT/scripts/plan-check.sh" "$ROOT/plan" >"$TMP/real.out"
assert_contains "plan-check accepts the real plan" "errors=0" "$(<"$TMP/real.out")"

cp -R "$ROOT/plan" "$TMP/plan-status"
sed -i 's#status: in_progress, started: 2026-09-16, depends_on:#status: todo, depends_on:#' "$TMP/plan-status/plan.yaml"
status_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status")"
assert_contains "plan-status lists P0-01 as runnable" $'--- runnable (todo, all deps done) ---\nP0-01' "$status_output"

printf '%s passed, %s failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
