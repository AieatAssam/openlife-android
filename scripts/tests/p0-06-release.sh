#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_GRADLE="$ROOT/app/build.gradle.kts"
PROGUARD="$ROOT/app/proguard-rules.pro"
PROPERTIES="$ROOT/gradle.properties"
CI_WORKFLOW="$ROOT/.github/workflows/ci.yml"
RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
RELEASE_VALIDATION="$ROOT/scripts/ci/run-release-validation.sh"
pass=0
fail=0

require() {
    local label="$1"
    local file="$2"
    local pattern="$3"
    if grep -Eq "$pattern" "$file"; then
        printf 'ok - %s\n' "$label"
        pass=$((pass + 1))
    else
        printf 'not ok - %s\nmissing pattern: %s\nfile: %s\n' "$label" "$pattern" "$file" >&2
        fail=$((fail + 1))
    fi
}

require_file() {
    local label="$1"
    local file="$2"
    if [[ -f "$file" ]]; then
        printf 'ok - %s\n' "$label"
        pass=$((pass + 1))
    else
        printf 'not ok - %s\nmissing file: %s\n' "$label" "$file" >&2
        fail=$((fail + 1))
    fi
}

require_executable() {
    local label="$1"
    local file="$2"
    if [[ -x "$file" ]]; then
        printf 'ok - %s\n' "$label"
        pass=$((pass + 1))
    else
        printf 'not ok - %s\nmissing executable: %s\n' "$label" "$file" >&2
        fail=$((fail + 1))
    fi
}

require "release enables R8 minification" "$BUILD_GRADLE" 'isMinifyEnabled = true'
require "release enables resource shrinking" "$BUILD_GRADLE" 'isShrinkResources = true'
require "full R8 mode is explicit" "$PROPERTIES" '^android\.enableR8\.fullMode=true$'
require "SQLCipher classes are retained for JNI" "$PROGUARD" '^-keep class net\.zetetic\.database\.\*\*'
require "Room generated implementations are retained" "$PROGUARD" '^-keep class org\.openlife\.vault\.storage\.\*\*_Impl'
require "coroutines debug-agent warnings are excluded" "$PROGUARD" 'kotlinx\.coroutines\.debug'
require "source and line attributes are retained" "$PROGUARD" '^-keepattributes SourceFile,LineNumberTable$'
require "source names remain mappable" "$PROGUARD" '^-renamesourcefileattribute SourceFile$'
require "release validation runs the release smoke" "$RELEASE_VALIDATION" 'scripts/release-smoke\.sh'
require "release validation checks native alignment" "$RELEASE_VALIDATION" 'scripts/check-16kb-alignment\.sh'
require_file "release validation script exists" "$RELEASE_VALIDATION"
require_executable "release validation script is executable" "$RELEASE_VALIDATION"
require "CI invokes release validation as one script" "$CI_WORKFLOW" 'scripts/ci/run-release-validation\.sh'
require "smoke launches the namespaced main activity" "$ROOT/scripts/release-smoke.sh" 'org\.openlife\.app\.MainActivity'
require "smoke launches the namespaced intake activity" "$ROOT/scripts/release-smoke.sh" 'org\.openlife\.app\.intake\.IntakeActivity'
require "smoke records media scan broadcast status" "$ROOT/scripts/release-smoke.sh" 'MEDIA_SCAN_BROADCAST_STATUS'
require "release evidence retains mapping" "$RELEASE_WORKFLOW" 'app/build/outputs/mapping/release/mapping\.txt'
require_file "release smoke script exists" "$ROOT/scripts/release-smoke.sh"
require_executable "release smoke script is executable" "$ROOT/scripts/release-smoke.sh"
require_file "16 KB alignment script exists" "$ROOT/scripts/check-16kb-alignment.sh"
require_executable "16 KB alignment script is executable" "$ROOT/scripts/check-16kb-alignment.sh"
require_file "release verification record exists" "$ROOT/docs/verification/release-build.md"

if grep -Eq 'isMinifyEnabled = false|^-dontobfuscate' "$BUILD_GRADLE" "$PROGUARD"; then
    printf 'not ok - release does not disable shrinking or obfuscation\n' >&2
    fail=$((fail + 1))
else
    printf 'ok - release does not disable shrinking or obfuscation\n'
    pass=$((pass + 1))
fi

printf '%s passed, %s failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
