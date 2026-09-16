#!/usr/bin/env bash
# Validates plan/plan.yaml and plan/steps/*.yaml. Uses the SnakeYAML jar that
# ships inside the Gradle wrapper distribution so no extra dependency is
# needed. Exit code 0 means valid.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" -name 'snakeyaml-*.jar' 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ]; then
  # Populate the wrapper distribution (offline-safe if already cached).
  (cd "$ROOT" && ./gradlew --version >/dev/null 2>&1) || true
  JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" -name 'snakeyaml-*.jar' 2>/dev/null | head -1 || true)"
fi
if [ -z "$JAR" ]; then echo "snakeyaml jar not found; run ./gradlew --version once" >&2; exit 2; fi
OUT="$(mktemp -d)"
javac -cp "$JAR" -d "$OUT" "$ROOT/plan/tools/PlanCheck.java"
java -cp "$JAR:$OUT" PlanCheck "$ROOT/plan"
