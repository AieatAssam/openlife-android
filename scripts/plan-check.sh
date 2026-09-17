#!/usr/bin/env bash
# Validates plan/plan.yaml and plan/steps/*.yaml. Uses the SnakeYAML jar that
# ships inside the Gradle wrapper distribution so no extra dependency or
# network access is needed. Exit code 0 means valid.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/plan.sh"
JSON=""
PLAN_ROOT="$ROOT/plan"
for arg in "$@"; do
  case "$arg" in
    --json) JSON="--json" ;;
    --*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) PLAN_ROOT="$arg" ;;
  esac
done
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists"
JAR="$(find "$GRADLE_CACHE" -type f -name 'snakeyaml-*.jar' -print -quit 2>/dev/null || true)"
if [ -z "$JAR" ]; then
  echo "snakeyaml jar not found under $GRADLE_CACHE; install the Gradle wrapper distribution first" >&2
  exit 2
fi
if [ -z "$JSON" ] && [ -z "$(plan_step_lines "$PLAN_ROOT/plan.yaml")" ]; then
  echo "$PLAN_ROOT/plan.yaml: phases: no one-line step entries found" >&2
  exit 1
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -cp "$JAR" -d "$OUT" "$ROOT/plan/tools/PlanCheck.java" >/dev/null 2>&1
java -cp "$JAR:$OUT" PlanCheck $JSON "$PLAN_ROOT"
