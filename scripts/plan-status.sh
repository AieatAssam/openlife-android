#!/usr/bin/env bash
# Prints every step with its status and lists runnable steps (todo with all
# dependencies done). Pure bash/awk over the one-line step entries in
# plan/plan.yaml.
set -euo pipefail
PLAN="$(cd "$(dirname "$0")/.." && pwd)/plan/plan.yaml"
declare -A status deps
while IFS= read -r line; do
  id=$(sed -n 's/.*{id: \([A-Z0-9-]*\),.*/\1/p' <<<"$line")
  [ -z "$id" ] && continue
  st=$(sed -n 's/.*status: \([a-z_]*\).*/\1/p' <<<"$line")
  dp=$(sed -n 's/.*depends_on: \[\([^]]*\)\].*/\1/p' <<<"$line" | tr -d ' ')
  status[$id]=$st; deps[$id]=$dp
  printf '%-7s %-12s deps=[%s]\n' "$id" "$st" "$dp"
done < <(grep -E '^\s+- \{id: P[0-9]+-[0-9]+,' "$PLAN")
echo "--- runnable (todo, all deps done) ---"
for id in "${!status[@]}"; do
  [ "${status[$id]}" = "todo" ] || continue
  ok=1
  IFS=',' read -ra ds <<<"${deps[$id]}"
  for d in "${ds[@]}"; do [ -z "$d" ] && continue; [ "${status[$d]:-todo}" = "done" ] || ok=0; done
  [ $ok = 1 ] && echo "$id"
done | sort
