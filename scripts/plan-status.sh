#!/usr/bin/env bash
# Prints every step with its status and lists runnable steps (todo with all
# dependencies done). It intentionally parses only the one-line flow-map step
# entries in plan/plan.yaml.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/plan.sh"
PLAN_ROOT="$ROOT/plan"
JSON=""
for arg in "$@"; do
  case "$arg" in
    --json) JSON=1 ;;
    --*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) PLAN_ROOT="$arg" ;;
  esac
done
PLAN="$PLAN_ROOT/plan.yaml"

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
  is_runnable "$id" && echo "$id"
done
true
