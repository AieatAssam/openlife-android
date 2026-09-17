#!/usr/bin/env bash

# Emits id|status|comma-separated-dependencies for each one-line step map.
# plan-status uses the records to report; plan-check uses the same parser as a
# lightweight flow-map preflight before its full YAML validation.
plan_strip_quoted() {
  awk '
    {
      quote = ""
      escaped = 0
      output = ""
      single = sprintf("%c", 39)
      for (i = 1; i <= length($0); i++) {
        character = substr($0, i, 1)
        if (quote != "") {
          if (quote == "\"" && character == "\\" && !escaped) {
            escaped = 1
          } else if (escaped) {
            escaped = 0
          } else if (character == quote) {
            quote = ""
          }
          output = output " "
        } else if (character == "\"" || character == single) {
          quote = character
          output = output " "
        } else {
          output = output character
        }
      }
      print output
    }
  '
}

plan_step_lines() {
  local plan="$1" line id status dep
  sed -nE '/^[[:space:]]+-[[:space:]]+\{id:[[:space:]]*P[0-9]+-[0-9]+,/p' "$plan" \
    | while IFS= read -r line; do
        plain_line=$(plan_strip_quoted <<<"$line")
        id=$(sed -nE 's/.*\{id:[[:space:]]*([A-Z0-9]+-[0-9]+),.*/\1/p' <<<"$plain_line")
        status=$(sed -nE 's/.*file:[[:space:]]*steps\/P[0-9]+-[0-9]+\.yaml,[[:space:]]*status:[[:space:]]*([a-z_]+).*/\1/p' <<<"$plain_line")
        dep=$(sed -nE 's/.*depends_on:[[:space:]]*\[([^]]*)\].*/\1/p' <<<"$plain_line" | tr -d '[:space:]')
        printf '%s|%s|%s\n' "$id" "$status" "$dep"
      done
}
