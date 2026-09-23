#!/usr/bin/env bash
# Generates brand screenshot goldens on the connected device and copies them
# into app/src/androidTest/assets/golden/<render-profile>/. Goldens are only
# valid for the render profile (API, system image product, density) that made
# them, so run this on each CI leg (workflow_dispatch input generate-goldens)
# or on a local emulator, then review and commit the PNGs.
#
# connectedDebugAndroidTest uninstalls the app when it finishes, which would
# delete the generated files; this script installs and instruments directly.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_CLASS="org.openlife.app.ui.brand.BrandScreenshotInstrumentedTest"
RUNNER="org.openlife.test/androidx.test.runner.AndroidJUnitRunner"
DEVICE_DIR="/sdcard/Android/data/org.openlife/files/goldens"
OUT_DIR="${1:-$ROOT/app/src/androidTest/assets/golden}"

cd "$ROOT"
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell rm -rf "$DEVICE_DIR" || true

result="$(adb shell am instrument -w -e class "$TEST_CLASS" -e generateGoldens true "$RUNNER")"
echo "$result"
if ! grep -q "^OK (1 test)" <<<"$(tr -d '\r' <<<"$result")"; then
  echo "golden generation did not pass" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
adb pull "$DEVICE_DIR/." "$OUT_DIR/"
find "$OUT_DIR" -name "*.png" -print | sort
