#!/usr/bin/env bash

# Emits id|status|comma-separated-dependencies for each one-line step map.
# plan-status uses the records to report; plan-check uses the same parser as a
# lightweight flow-map preflight before its full YAML validation.
plan_flow_fields() {
  awk '
    {
      start = index($0, "{")
      line = substr($0, start + 1)
      sub(/[}][[:space:]]*$/, "", line)
      quote = ""
      escaped = 0
      depth = 0
      field = ""
      single = sprintf("%c", 39)
      for (i = 1; i <= length(line); i++) {
        character = substr(line, i, 1)
        if (quote != "") {
          if (quote == "\"" && character == "\\" && !escaped) {
            escaped = 1
          } else if (escaped) {
            escaped = 0
          } else if (character == quote) {
            quote = ""
          }
          field = field character
        } else if (character == "\"" || character == single) {
          quote = character
          field = field character
        } else if (character == "[" || character == "{") {
          depth++
          field = field character
        } else if (character == "]" || character == "}") {
          depth--
          field = field character
        } else if (character == "," && depth == 0) {
          print_field(field)
          field = ""
        } else {
          field = field character
        }
      }
      print_field(field)
    }
    function print_field(value, separator, key, content) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      separator = index(value, ":")
      if (separator == 0) {
        return
      }
      key = substr(value, 1, separator - 1)
      content = substr(value, separator + 1)
      sub(/^[[:space:]]+/, "", key)
      sub(/[[:space:]]+$/, "", key)
      sub(/^[[:space:]]+/, "", content)
      sub(/[[:space:]]+$/, "", content)
      print key "|" content
    }
  '
}

plan_field() {
  local key="$1"
  awk -F'|' -v wanted="$key" 'BEGIN { single = sprintf("%c", 39) } $1 == wanted { value = $0; sub(/^[^|]*\|/, "", value); if (substr(value, 1, 1) == "\"") { sub(/^"/, "", value); sub(/"$/, "", value) } else if (substr(value, 1, 1) == single) { sub("^" single, "", value); sub(single "$", "", value) } print value; exit }'
}

plan_step_lines() {
  local plan="$1" line id status dep
  sed -nE '/^[[:space:]]+-[[:space:]]+\{id:/p' "$plan" \
    | while IFS= read -r line; do
        fields=$(plan_flow_fields <<<"$line")
        id=$(plan_field id <<<"$fields")
        status=$(plan_field status <<<"$fields")
        dep=$(plan_field depends_on <<<"$fields" | tr -d '[][:space:]')
        printf '%s|%s|%s\n' "$id" "$status" "$dep"
      done
}
