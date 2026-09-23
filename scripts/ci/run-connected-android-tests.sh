#!/usr/bin/env bash
# Invoked by android-emulator-runner's `script` input. That action runs
# `sh -c`, which is dash on Ubuntu and rejects bash-only options such as
# `set -o pipefail` and `${PIPESTATUS[@]}`. Keep this file on bash.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
API_LEVEL="${1:-${OPENLIFE_API_LEVEL:?api level required}}"
LOGCAT_FILE="logcat-openlife-${API_LEVEL}.txt"
CLASSIFIER="$ROOT/scripts/ci/connected-test-failure-is-assertion.sh"

dump_logcat() {
  timeout 20s adb logcat -d 2>/dev/null \
    | grep -E 'org\.openlife|OpenLifeRecovery' >"$LOGCAT_FILE" \
    || true
}

trap dump_logcat EXIT

cd "$ROOT"
# The emulator must not carry an OpenLife signed with another key (for
# example the release smoke's) or the debug install fails.
adb uninstall org.openlife >/dev/null 2>&1 || true
adb uninstall org.openlife.test >/dev/null 2>&1 || true
for attempt in 1 2; do
  output="connected-attempt-${API_LEVEL}-${attempt}.log"
  set +e
  ./gradlew :app:connectedDebugAndroidTest :vault:connectedDebugAndroidTest 2>&1 | tee "$output"
  status=${PIPESTATUS[0]}
  set -e
  if [[ "$status" -eq 0 ]]; then
    bash "$ROOT/scripts/ci/assert-connected-tests-ran.sh" app vault
    exit $?
  fi
  if "$CLASSIFIER" "$output"; then
    echo "connected test assertion failure; not retrying" >&2
    exit "$status"
  fi
  if [[ "$attempt" -eq 2 ]]; then
    exit "$status"
  fi
  timeout 60s adb wait-for-device || true
done
