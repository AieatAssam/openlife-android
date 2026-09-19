#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if ! command -v actionlint >/dev/null 2>&1; then
  printf 'actionlint is required to lint .github/workflows/release.yml\n' >&2
  exit 1
fi

actionlint "$ROOT/.github/workflows/release.yml"
