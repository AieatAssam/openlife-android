#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

./gradlew assembleRelease

release_apk="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$release_apk" ]]; then
    release_apk="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

bash scripts/check-16kb-alignment.sh "$release_apk"
bash scripts/release-smoke.sh "$release_apk"
