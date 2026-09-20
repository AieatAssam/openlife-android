#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'usage: %s <release-apk>\n' "$0" >&2
    exit 2
}

[[ $# -eq 1 ]] || usage
apk="$1"
[[ -f "$apk" ]] || { printf 'release APK not found: %s\n' "$apk" >&2; exit 1; }

adb_bin="$(printenv ADB 2>/dev/null || true)"
if [[ -z "$adb_bin" ]]; then
    adb_bin="adb"
fi
package="org.openlife"
main_activity="$package/.MainActivity"
intake_activity="$package/.intake.IntakeActivity"
tmp="$(mktemp -d)"
ui_dump="$tmp/window.xml"
remote_file="/sdcard/Pictures/openlife-release-smoke-$BASHPID.png"
trap 'rm -rf "$tmp"' EXIT

"$adb_bin" wait-for-device

find_sdk_tool() {
    local tool="$1"
    local sdk_root=""
    sdk_root="$(printenv ANDROID_SDK_ROOT 2>/dev/null || true)"
    if [[ -z "$sdk_root" ]]; then
        sdk_root="$(printenv ANDROID_HOME 2>/dev/null || true)"
    fi
    if [[ -n "$sdk_root" ]]; then
        find "$sdk_root/build-tools" -type f -name "$tool" -perm -111 -print 2>/dev/null | sort | tail -n 1
    fi
}

find_tool() {
    local tool="$1"
    local sdk_tool
    local store_tool
    sdk_tool="$(find_sdk_tool "$tool")"
    if [[ -n "$sdk_tool" ]]; then
        printf '%s\n' "$sdk_tool"
    elif command -v "$tool" >/dev/null 2>&1; then
        command -v "$tool"
    else
        store_tool="$(find /nix/store -type f -name "$tool" -perm -111 -print 2>/dev/null | sort | tail -n 1)"
        [[ -n "$store_tool" ]] || return 1
        printf '%s\n' "$store_tool"
    fi
}

dump_ui() {
    "$adb_bin" shell uiautomator dump /sdcard/openlife-release-smoke.xml >/dev/null 2>&1 || return 1
    "$adb_bin" shell cat /sdcard/openlife-release-smoke.xml | tr -d '\r' > "$ui_dump"
}

wait_for_ui_pattern() {
    local pattern="$1"
    local timeout_seconds=45
    if [[ $# -ge 2 ]]; then
        timeout_seconds="$2"
    fi
    local deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < deadline )); do
        if dump_ui && grep -Eq "$pattern" "$ui_dump"; then
            return 0
        fi
        sleep 1
    done
    printf 'timed out waiting for UI pattern: %s\n' "$pattern" >&2
    return 1
}

node_bounds() {
    local attribute="$1"
    local value="$2"
    grep -o "<node[^>]*$attribute=\"$value\"[^>]*>" "$ui_dump" | head -n 1 |
        sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'
}

tap_node() {
    local attribute="$1"
    local value="$2"
    local bounds
    bounds="$(node_bounds "$attribute" "$value")"
    if [[ -z "$bounds" ]]; then
        printf 'UI node not found: %s=%s\n' "$attribute" "$value" >&2
        return 1
    fi
    read -r left top right bottom <<< "$bounds"
    "$adb_bin" shell input tap "$(((left + right) / 2))" "$(((top + bottom) / 2))"
}

tap_text() {
    tap_node text "$1"
}

tap_description() {
    tap_node 'content-desc' "$1"
}

count_descriptions() {
    { grep -o 'content-desc="Saved image thumbnail"' "$ui_dump" || true; } |
        wc -l | tr -d '[:space:]'
}

wait_for_thumbnail_count() {
    local expected="$1"
    local timeout_seconds=45
    if [[ $# -ge 2 ]]; then
        timeout_seconds="$2"
    fi
    local deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < deadline )); do
        if dump_ui && [[ "$(count_descriptions)" -eq "$expected" ]]; then
            return 0
        fi
        sleep 1
    done
    printf 'timed out waiting for thumbnail count: %s\n' "$expected" >&2
    return 1
}

assert_no_fatal_exception() {
    if "$adb_bin" logcat -d -v brief | grep -Eq 'FATAL EXCEPTION|Process: org\.openlife'; then
        printf 'release smoke found an app crash in logcat\n' >&2
        return 1
    fi
}

apksigner="$(find_tool apksigner)"
keytool_bin="$(command -v keytool)"
install_apk="$apk"

# assembleRelease intentionally produces an unsigned APK when CI signing
# secrets are absent. Install a disposable, non-debug smoke copy in that case;
# the original release output and its mapping remain untouched and the smoke
# certificate is never a distribution credential.
if ! "$apksigner" verify "$apk" >/dev/null 2>&1; then
    smoke_keystore="$tmp/smoke-upload.jks"
    smoke_password="openlife-smoke-only"
    "$keytool_bin" -genkeypair \
        -alias openlife-smoke \
        -keyalg RSA \
        -keysize 2048 \
        -validity 1 \
        -keystore "$smoke_keystore" \
        -storepass "$smoke_password" \
        -keypass "$smoke_password" \
        -dname 'CN=OpenLife release smoke, OU=Tests, O=OpenLife, L=Local, ST=Local, C=XX' \
        >/dev/null 2>&1
    install_apk="$tmp/release-smoke-signed.apk"
    "$apksigner" sign \
        --ks "$smoke_keystore" \
        --ks-key-alias openlife-smoke \
        --ks-pass "pass:$smoke_password" \
        --key-pass "pass:$smoke_password" \
        --out "$install_apk" \
        "$apk"
    printf 'SMOKE_INSTALL_SIGNING=disposable\n'
else
    printf 'SMOKE_INSTALL_SIGNING=release-certificate\n'
fi

"$adb_bin" install -r "$install_apk" >/dev/null
"$adb_bin" logcat -c
"$adb_bin" shell am force-stop "$package"
"$adb_bin" shell am start -W -n "$main_activity" >/dev/null

if wait_for_ui_pattern 'text="I understand"' 8; then
    tap_text 'I understand'
fi
wait_for_ui_pattern 'text="OpenLife"|text="Nothing imported yet"|content-desc="Saved image thumbnail"' 45
dump_ui
baseline_thumbnail_count="$(count_descriptions)"

# This PNG is synthetic and intentionally generated by the test script; it
# carries no user data and exercises the same MediaStore/share boundary as a
# third-party image provider.
base64 --decode > "$tmp/smoke.png" <<'PNG'
iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=
PNG
"$adb_bin" push "$tmp/smoke.png" "$remote_file" >/dev/null
"$adb_bin" shell am broadcast \
    -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://$remote_file" >/dev/null

media_id=""
for _ in $(seq 1 30); do
    query="$("$adb_bin" shell content query \
        --uri content://media/external/images/media \
        --projection _id:_data 2>/dev/null | tr -d '\r')"
    media_id="$(printf '%s\n' "$query" | grep -F "$remote_file" | sed -n 's/.*_id=\([^,]*\).*/\1/p' | head -n 1)"
    [[ -n "$media_id" ]] && break
    sleep 1
done
[[ -n "$media_id" ]] || { printf 'media scanner did not expose the synthetic PNG\n' >&2; exit 1; }
media_uri="content://media/external/images/media/$media_id"

"$adb_bin" shell am start -W \
    -a android.intent.action.SEND \
    -t image/png \
    --eu android.intent.extra.STREAM "$media_uri" \
    --grant-read-uri-permission \
    -n "$intake_activity" >/dev/null
if wait_for_ui_pattern 'text="I understand"' 8; then
    tap_text 'I understand'
fi
wait_for_ui_pattern 'text="PNG[^<]*"|content-desc="Selected image preview"' 60
tap_text 'Save'
wait_for_ui_pattern 'text="Saved on this device"|content-desc="Saved image thumbnail"' 60
sleep 1
"$adb_bin" shell am force-stop "$package"
"$adb_bin" shell am start -W -n "$main_activity" >/dev/null
wait_for_ui_pattern 'content-desc="Saved image thumbnail"' 45
dump_ui
after_save_thumbnail_count="$(count_descriptions)"
(( after_save_thumbnail_count > baseline_thumbnail_count ))
tap_description 'Saved image thumbnail'
wait_for_ui_pattern 'text="Verified against the saved copy"' 45
tap_description 'Delete'
wait_for_ui_pattern 'text="There is no undo"' 15
tap_text 'Delete'
wait_for_thumbnail_count "$baseline_thumbnail_count" 45
after_delete_thumbnail_count="$(count_descriptions)"
[[ "$after_delete_thumbnail_count" -eq "$baseline_thumbnail_count" ]]
assert_no_fatal_exception

printf 'RELEASE_SMOKE_RESULT=pass\n'
printf 'THUMBNAILS_BEFORE=%s\n' "$baseline_thumbnail_count"
printf 'THUMBNAILS_AFTER_DELETE=%s\n' "$after_delete_thumbnail_count"
