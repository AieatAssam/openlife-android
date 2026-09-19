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

assert_not_contains() {
  local label="$1" unexpected="$2" actual="$3"
  if [[ "$actual" != *"$unexpected"* ]]; then
    printf 'ok - %s\n' "$label"
    pass=$((pass + 1))
  else
    printf 'not ok - %s\nunexpected to find: %s\nactual:\n%s\n' "$label" "$unexpected" "$actual" >&2
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

cp -R "$ROOT/plan" "$TMP/plan-missing-key"
sed -i '/^owner: agent$/d' "$TMP/plan-missing-key/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing-key" >"$TMP/missing-key.out" 2>&1
missing_key_status=$?
set -e
assert_nonzero "plan-check rejects a missing required key" "$TMP/missing-key.out" "$missing_key_status"
assert_contains "missing-key diagnostic names file and key" "P0-01.yaml: owner: missing key" "$(<"$TMP/missing-key.out")"

cp -R "$ROOT/plan" "$TMP/plan-missing-requirement-id"
sed -i '0,/^  - id: P0-01-R1$/s//  -/' "$TMP/plan-missing-requirement-id/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing-requirement-id" >"$TMP/missing-requirement-id.out" 2>&1
missing_requirement_id_status=$?
set -e
assert_nonzero "plan-check rejects a missing requirement id" "$TMP/missing-requirement-id.out" "$missing_requirement_id_status"
assert_contains "missing-requirement-id diagnostic names key" "requirements[0].id: missing key" "$(<"$TMP/missing-requirement-id.out")"

cp -R "$ROOT/plan" "$TMP/plan-wrong-requirement-prefix"
sed -i '0,/id: P0-01-R1/s//id: P0-02-R1/' "$TMP/plan-wrong-requirement-prefix/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-wrong-requirement-prefix" >"$TMP/wrong-requirement-prefix.out" 2>&1
wrong_requirement_prefix_status=$?
set -e
assert_nonzero "plan-check rejects a wrong requirement step prefix" "$TMP/wrong-requirement-prefix.out" "$wrong_requirement_prefix_status"
assert_contains "wrong-requirement-prefix diagnostic names expected step" "must start with P0-01-R" "$(<"$TMP/wrong-requirement-prefix.out")"

cp -R "$ROOT/plan" "$TMP/plan-missing-verification-command"
sed -i '0,/^  commands:$/s//  wrong_commands:/' "$TMP/plan-missing-verification-command/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing-verification-command" >"$TMP/missing-verification-command.out" 2>&1
missing_verification_command_status=$?
set -e
assert_nonzero "plan-check rejects a missing verification command list" "$TMP/missing-verification-command.out" "$missing_verification_command_status"
assert_contains "missing-verification-command diagnostic names key" "verification.commands: missing key" "$(<"$TMP/missing-verification-command.out")"

bash "$ROOT/scripts/plan-check.sh" "$ROOT/plan" >"$TMP/real.out"
assert_contains "plan-check accepts the real plan" "errors=0" "$(<"$TMP/real.out")"

cp -R "$ROOT/plan" "$TMP/plan-status"
sed -i -E 's/status: (in_progress|review),/status: todo,/' "$TMP/plan-status/plan.yaml"
sed -i 's/status: done,/status: todo,/' "$TMP/plan-status/plan.yaml"
status_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status")"
assert_contains "plan-status lists P0-01 as runnable" $'--- runnable (todo, all deps done) ---\nP0-01' "$status_output"

cp -R "$ROOT/plan" "$TMP/plan-status-quoted-dependency"
sed -i 's/status: review,/status: done,/' "$TMP/plan-status-quoted-dependency/plan.yaml"
sed -i 's/\(id: P0-02, title: .*file: steps\/P0-02.yaml, status: \)done/\1todo/' "$TMP/plan-status-quoted-dependency/plan.yaml"
sed -i "s/depends_on: \[P0-01\]/depends_on: ['P0-01']/g" "$TMP/plan-status-quoted-dependency/plan.yaml"
quoted_dependency_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status-quoted-dependency")"
assert_contains "plan-status recognizes quoted dependency items" $'--- runnable (todo, all deps done) ---\nP0-02' "$quoted_dependency_output"

cp -R "$ROOT/plan" "$TMP/plan-status-double-quoted-dependency"
sed -i 's/status: review,/status: done,/' "$TMP/plan-status-double-quoted-dependency/plan.yaml"
sed -i 's/\(id: P0-02, title: .*file: steps\/P0-02.yaml, status: \)done/\1todo/' "$TMP/plan-status-double-quoted-dependency/plan.yaml"
sed -i 's/depends_on: \[P0-01\]/depends_on: ["P0-01"]/g' "$TMP/plan-status-double-quoted-dependency/plan.yaml"
double_quoted_dependency_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status-double-quoted-dependency")"
assert_contains "plan-status recognizes double-quoted dependency items" $'--- runnable (todo, all deps done) ---\nP0-02' "$double_quoted_dependency_output"

real_status_output="$(bash "$ROOT/scripts/plan-status.sh" "$ROOT/plan")"
assert_not_contains "plan-status excludes review-finding entries" "F-47" "$real_status_output"

cp -R "$ROOT/plan" "$TMP/plan-status-collision"
sed -i 's/notes: "Acceptance criteria met; awaiting review by an agent other than the implementer\."/notes: "quoted status: todo, is not a field"/' "$TMP/plan-status-collision/plan.yaml"
collision_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status-collision")"
assert_not_contains "plan-status ignores status text inside notes" $'--- runnable (todo, all deps done) ---\nP0-01' "$collision_output"

cp -R "$ROOT/plan" "$TMP/plan-status-title-collision"
sed -i 's/title: Plan conventions and status tooling/title: "Plan conventions; file: steps\/P0-01.yaml, status: todo,"/' "$TMP/plan-status-title-collision/plan.yaml"
title_collision_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status-title-collision")"
assert_not_contains "plan-status ignores status text inside a title" $'--- runnable (todo, all deps done) ---\nP0-01' "$title_collision_output"

cp -R "$ROOT/plan" "$TMP/plan-status-quoted-structural"
sed -i 's/{id: P0-01, title:/{id: "P0-01", title:/' "$TMP/plan-status-quoted-structural/plan.yaml"
sed -i 's/file: steps\/P0-01.yaml, status:/file: "steps\/P0-01.yaml", status:/' "$TMP/plan-status-quoted-structural/plan.yaml"
quoted_structural_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status-quoted-structural")"
assert_contains "plan-status preserves quoted structural fields" "P0-01   done" "$quoted_structural_output"
assert_not_contains "quoted structural fields do not create a runnable step" $'--- runnable (todo, all deps done) ---\nP0-01' "$quoted_structural_output"

cp -R "$ROOT/plan" "$TMP/plan-status-single-quoted-structural"
sed -i "s/{id: P0-01, title:/{id: 'P0-01', title:/" "$TMP/plan-status-single-quoted-structural/plan.yaml"
sed -i "s/file: steps\/P0-01.yaml, status:/file: 'steps\/P0-01.yaml', status:/" "$TMP/plan-status-single-quoted-structural/plan.yaml"
single_quoted_structural_output="$(bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-status-single-quoted-structural")"
assert_contains "plan-status preserves single-quoted structural fields" "P0-01   done" "$single_quoted_structural_output"

cp -R "$ROOT/plan" "$TMP/plan-phase-mismatch"
sed -i '0,/^phase: P0$/s//phase: P1/' "$TMP/plan-phase-mismatch/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-phase-mismatch" >"$TMP/phase-mismatch.out" 2>&1
phase_mismatch_status=$?
set -e
assert_nonzero "plan-check rejects a companion phase mismatch" "$TMP/phase-mismatch.out" "$phase_mismatch_status"
assert_contains "phase-mismatch diagnostic names key" "phase: must be P0" "$(<"$TMP/phase-mismatch.out")"

cp -R "$ROOT/plan" "$TMP/plan-missing-phase-key"
sed -i '/^    title: Engineering foundation$/d' "$TMP/plan-missing-phase-key/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing-phase-key" >"$TMP/missing-phase-key.out" 2>&1
missing_phase_key_status=$?
set -e
assert_nonzero "plan-check rejects a missing phase key" "$TMP/missing-phase-key.out" "$missing_phase_key_status"
assert_contains "missing-phase-key diagnostic names key" "phases[P0].title: missing key" "$(<"$TMP/missing-phase-key.out")"

cp -R "$ROOT/plan" "$TMP/plan-missing-master-key"
sed -i 's/, owner: agent}/}/' "$TMP/plan-missing-master-key/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing-master-key" >"$TMP/missing-master-key.out" 2>&1
missing_master_key_status=$?
set -e
assert_nonzero "plan-check rejects a missing master-step key" "$TMP/missing-master-key.out" "$missing_master_key_status"
assert_contains "missing-master-key diagnostic names key" "step P0-01.owner: missing key" "$(<"$TMP/missing-master-key.out")"

cp -R "$ROOT/plan" "$TMP/plan-duplicate-key"
sed -i '1a schema_version: 1' "$TMP/plan-duplicate-key/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-duplicate-key" >"$TMP/duplicate-key.out" 2>&1
duplicate_key_status=$?
set -e
assert_nonzero "plan-check rejects duplicate YAML keys" "$TMP/duplicate-key.out" "$duplicate_key_status"
assert_contains "duplicate-key diagnostic is present" "duplicate" "$(<"$TMP/duplicate-key.out")"

mkdir -p "$TMP/plan-no-file/steps"
cp "$ROOT/plan/steps/P0-01.yaml" "$TMP/plan-no-file/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-status.sh" "$TMP/plan-no-file" >"$TMP/no-plan-file.out" 2>&1
no_plan_file_status=$?
set -e
assert_nonzero "plan-status rejects a missing plan file" "$TMP/no-plan-file.out" "$no_plan_file_status"

cp -R "$ROOT/plan" "$TMP/plan-empty"
: > "$TMP/plan-empty/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" --json "$TMP/plan-empty" >"$TMP/empty-json.out" 2>&1
empty_json_status=$?
set -e
assert_nonzero "plan-check JSON rejects an empty plan" "$TMP/empty-json.out" "$empty_json_status"
assert_contains "empty JSON failure remains machine-readable" '"valid":false' "$(<"$TMP/empty-json.out")"

cp -R "$ROOT/plan" "$TMP/plan-missing-finding-severity"
sed -i '0,/severity: high/s//severity_missing: high/' "$TMP/plan-missing-finding-severity/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-missing-finding-severity" >"$TMP/missing-finding-severity.out" 2>&1
missing_finding_severity_status=$?
set -e
assert_nonzero "plan-check rejects incomplete review finding metadata" "$TMP/missing-finding-severity.out" "$missing_finding_severity_status"
assert_contains "finding-severity diagnostic names key" "finding F-01.severity: missing key" "$(<"$TMP/missing-finding-severity.out")"

cp -R "$ROOT/plan" "$TMP/plan-done-without-evidence"
sed -i 's/status: review/status: done/' "$TMP/plan-done-without-evidence/plan.yaml"
sed -i 's/, evidence: "[^"]*"//' "$TMP/plan-done-without-evidence/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-done-without-evidence" >"$TMP/done-without-evidence.out" 2>&1
done_without_evidence_status=$?
set -e
assert_nonzero "plan-check rejects done without evidence" "$TMP/done-without-evidence.out" "$done_without_evidence_status"
assert_contains "done-evidence diagnostic names key" "step P0-01.evidence: required when status is done" "$(<"$TMP/done-without-evidence.out")"

cp -R "$ROOT/plan" "$TMP/plan-owner-action-unknown-step"
sed -i '0,/step: P0-05/s//step: P99-99/' "$TMP/plan-owner-action-unknown-step/plan.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-owner-action-unknown-step" >"$TMP/owner-action-unknown-step.out" 2>&1
owner_action_unknown_step_status=$?
set -e
assert_nonzero "plan-check rejects an owner action with an unknown step" "$TMP/owner-action-unknown-step.out" "$owner_action_unknown_step_status"
assert_contains "owner-action diagnostic names step" "owner action OA-1.step: unknown step P99-99" "$(<"$TMP/owner-action-unknown-step.out")"

cp -R "$ROOT/plan" "$TMP/plan-symlink"
mv "$TMP/plan-symlink/steps/P0-01.yaml" "$TMP/plan-symlink/steps/P0-01.real.yaml"
ln -s P0-02.yaml "$TMP/plan-symlink/steps/P0-01.yaml"
set +e
bash "$ROOT/scripts/plan-check.sh" "$TMP/plan-symlink" >"$TMP/symlink.out" 2>&1
symlink_status=$?
set -e
assert_nonzero "plan-check rejects symlinked step files" "$TMP/symlink.out" "$symlink_status"
assert_contains "symlink diagnostic is present" "symlink" "$(<"$TMP/symlink.out")"

set +e
bash "$ROOT/scripts/plan-check.sh" "$ROOT/plan" "$ROOT/plan" >"$TMP/check-two-paths.out" 2>&1
two_check_paths_status=$?
set -e
assert_nonzero "plan-check rejects multiple plan paths" "$TMP/check-two-paths.out" "$two_check_paths_status"

set +e
bash "$ROOT/scripts/plan-status.sh" "$ROOT/plan" "$ROOT/plan" >"$TMP/status-two-paths.out" 2>&1
two_status_paths_status=$?
set -e
assert_nonzero "plan-status rejects multiple plan paths" "$TMP/status-two-paths.out" "$two_status_paths_status"

stage_comments="$(grep -rn --exclude-dir=build 'Stage [0-9]' "$ROOT/app" "$ROOT/vault" || true)"
assert_not_contains "app and vault have no implementation-stage comments" "Stage " "$stage_comments"

set +e
bash "$ROOT/scripts/tests/p0-04-ci-workflow.sh" >"$TMP/p0-04-ci-workflow.out" 2>&1
ci_workflow_status=$?
set -e
if [[ "$ci_workflow_status" -eq 0 ]]; then
  printf 'ok - P0-04 CI workflow contract passes\n'
  pass=$((pass + 1))
else
  printf 'not ok - P0-04 CI workflow contract passes\n%s\n' "$(<"$TMP/p0-04-ci-workflow.out")" >&2
  fail=$((fail + 1))
fi

set +e
bash "$ROOT/scripts/tests/p0-05-release-workflow.sh" >"$TMP/p0-05-release-workflow.out" 2>&1
release_workflow_status=$?
set -e
if [[ "$release_workflow_status" -eq 0 ]]; then
  printf 'ok - P0-05 release workflow lint passes\n'
  pass=$((pass + 1))
else
  printf 'not ok - P0-05 release workflow lint passes\n%s\n' "$(<"$TMP/p0-05-release-workflow.out")" >&2
  fail=$((fail + 1))
fi

printf '%s passed, %s failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
