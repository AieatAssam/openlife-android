#!/usr/bin/env bash
# Hosted connected-test wrapper for P0-04. android-emulator-runner v2.38+
# invokes the `script` input with /usr/bin/sh (dash on Ubuntu), which rejects
# `set -o pipefail` and has no PIPESTATUS. Keep bash-only logic here.
set -euo pipefail

api_level="${1:?api level required}"

trap 'timeout 20s adb logcat -d 2>/dev/null | grep -E "org\\.openlife|OpenLifeRecovery" > "logcat-openlife-${api_level}.txt" || true' EXIT

for attempt in 1 2; do
  output="connected-attempt-${api_level}-${attempt}.log"
  set +e
  ./gradlew :app:connectedDebugAndroidTest :vault:connectedDebugAndroidTest 2>&1 | tee "$output"
  status=${PIPESTATUS[0]}
  set -e
  if [[ "${status}" -eq 0 ]]; then
    exit 0
  fi
  if scripts/ci/connected-test-failure-is-assertion.sh "${output}"; then
    echo "connected test assertion failure; not retrying" >&2
    exit "${status}"
  fi
  if [[ "${attempt}" -eq 2 ]]; then
    exit "${status}"
  fi
  timeout 60s adb wait-for-device || true
done
