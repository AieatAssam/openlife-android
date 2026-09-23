#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if ! command -v actionlint >/dev/null 2>&1; then
  if grep -Eq 'raven-actions/actionlint@[0-9a-f]{40}' "$ROOT/.github/workflows/release.yml"; then
    printf 'ok - release workflow delegates linting to pinned raven-actions/actionlint\n'
    exit 0
  fi
  printf 'actionlint or a pinned actionlint action is required to lint .github/workflows/release.yml\n' >&2
  exit 1
fi

actionlint "$ROOT/.github/workflows/release.yml"
