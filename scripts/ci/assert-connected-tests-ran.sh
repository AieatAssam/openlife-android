#!/usr/bin/env bash
# Fails unless every listed module produced connected-test results with at
# least one executed test. A connected task can report success after an APK
# install failure with zero tests run (PR #8 run 35897004928:
# INSTALL_FAILED_UPDATE_INCOMPATIBLE, "BUILD SUCCESSFUL", 0 tests), so a green
# Gradle exit alone is not evidence.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
modules=("$@")
[[ ${#modules[@]} -gt 0 ]] || modules=(app vault)

status=0
for module in "${modules[@]}"; do
  results_dir="$ROOT/$module/build/outputs/androidTest-results/connected/debug"
  count="$(python3 - "$results_dir" <<'PY'
import glob, sys
import xml.etree.ElementTree as ET
total = 0
for path in glob.glob(sys.argv[1] + "/*.xml"):
    total += sum(1 for _ in ET.parse(path).getroot().iter("testcase"))
print(total)
PY
)"
  echo "CONNECTED_TESTS_RAN ${module}=${count}"
  if [[ "$count" -eq 0 ]]; then
    echo "no connected tests ran for :${module}; refusing to report success" >&2
    status=1
  fi
done
exit "$status"
