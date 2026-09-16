#!/usr/bin/env bash

# Emits id|status|comma-separated-dependencies for each one-line step map.
# plan-status uses the records to report; plan-check uses the same parser as a
# lightweight flow-map preflight before its full YAML validation.
plan_step_lines() {
  local plan="$1" line id status dep
  sed -nE '/^[[:space:]]+-[[:space:]]+\{id:[[:space:]]*P[0-9]+-[0-9]+,/p' "$plan" \
    | while IFS= read -r line; do
        id=$(sed -nE 's/.*\{id:[[:space:]]*([A-Z0-9]+-[0-9]+),.*/\1/p' <<<"$line")
        status=$(sed -nE 's/.*status:[[:space:]]*([a-z_]+)(,|\}).*/\1/p' <<<"$line")
        dep=$(sed -nE 's/.*depends_on:[[:space:]]*\[([^]]*)\].*/\1/p' <<<"$line" | tr -d '[:space:]')
        printf '%s|%s|%s\n' "$id" "$status" "$dep"
      done
}
