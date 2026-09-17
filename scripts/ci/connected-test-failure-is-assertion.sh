#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "usage: $0 <connected-test-log>" >&2
  exit 2
fi

# Generic instrumentation shortMsg values such as Process crashed or
# DeadSystemException describe infrastructure failures and remain retryable.
# Assertion-shaped output is terminal, including both bare and parenthesized
# Android runner forms.
grep -Eiq \
  'There were failing tests|AssertionError|junit\.framework\.AssertionFailedError|INSTRUMENTATION_RESULT: shortMsg=[(]?(Test failed|Test run failed|Assertion|junit|java\.lang\.AssertionError|org\.junit)' \
  "$1"
