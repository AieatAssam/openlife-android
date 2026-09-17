#!/usr/bin/env bash
# Prints every step with its status and lists runnable steps (todo with all
# dependencies done). It intentionally parses only the one-line flow-map step
# entries in plan/plan.yaml.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/plan.sh"
PLAN_ROOT="$ROOT/plan"
JSON=""
PLAN_ARG_SET=0
for arg in "$@"; do
  case "$arg" in
    --json) JSON=1 ;;
    --*) echo "unknown option: $arg" >&2; exit 2 ;;
    *)
      [ "$PLAN_ARG_SET" -eq 0 ] || { echo "expected one plan directory, got: $arg" >&2; exit 2; }
      PLAN_ROOT="$arg"
      PLAN_ARG_SET=1
      ;;
  esac
done
PLAN="$PLAN_ROOT/plan.yaml"
[ -f "$PLAN" ] || { echo "$PLAN: missing plan file" >&2; exit 1; }

declare -a ids statuses dependencies
declare -A status_by_id deps_by_id
while IFS='|' read -r id status dep; do
  [ -z "$id" ] && continue
  ids+=("$id")
  statuses+=("$status")
  dependencies+=("$dep")
  status_by_id["$id"]="$status"
  deps_by_id["$id"]="$dep"
done < <(plan_step_lines "$PLAN")

if [ "${#ids[@]}" -eq 0 ]; then
  echo "$PLAN: no one-line step entries found" >&2
  exit 1
fi

is_runnable() {
  local id="$1" dep
  [ "${status_by_id[$id]:-}" = todo ] || return 1
  IFS=',' read -ra dep_list <<<"${deps_by_id[$id]:-}"
  for dep in "${dep_list[@]}"; do
    [ -z "$dep" ] && continue
    [ "${status_by_id[$dep]:-}" = done ] || return 1
  done
}

if [ -n "$JSON" ]; then
  printf '{"steps":['
  for i in "${!ids[@]}"; do
    [ "$i" -eq 0 ] || printf ','
    printf '{"id":"%s","status":"%s","depends_on":[' "${ids[$i]}" "${statuses[$i]}"
    IFS=',' read -ra dep_list <<<"${dependencies[$i]}"
    first=1
    for dep in "${dep_list[@]}"; do
      [ -z "$dep" ] && continue
      [ "$first" -eq 1 ] || printf ','
      printf '"%s"' "$dep"
      first=0
    done
    printf ']}'
  done
  printf '],"runnable":['
  first=1
  for id in "${ids[@]}"; do
    if is_runnable "$id"; then
      [ "$first" -eq 1 ] || printf ','
      printf '"%s"' "$id"
      first=0
    fi
  done
  printf ']}\n'
  exit 0
fi

for i in "${!ids[@]}"; do
  printf '%-7s %-12s deps=[%s]\n' "${ids[$i]}" "${statuses[$i]}" "${dependencies[$i]}"
done
echo "--- runnable (todo, all deps done) ---"
for id in "${ids[@]}"; do
  if is_runnable "$id"; then
    echo "$id"
  fi
done
