#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <release-apk>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
apk="$1"
[[ -f "$apk" ]] || { echo "release APK not found: $apk" >&2; exit 1; }

find_aapt2() {
  if [[ -n "${AAPT2_PATH:-}" && -x "$AAPT2_PATH" ]]; then
    printf '%s\n' "$AAPT2_PATH"
    return
  fi
  if command -v aapt2 >/dev/null 2>&1; then
    command -v aapt2
    return
  fi
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$sdk_root" ]]; then
    find -L "$sdk_root/build-tools" -maxdepth 2 -type f -name aapt2 -perm -u+x 2>/dev/null |
      sort -V | tail -n 1
  fi
}

aapt2="$(find_aapt2)"
[[ -n "$aapt2" && -x "$aapt2" ]] || {
  echo "aapt2 is required; set AAPT2_PATH or ANDROID_SDK_ROOT" >&2
  exit 1
}

permissions="$($aapt2 dump permissions "$apk")"
badging="$($aapt2 dump badging "$apk")"
manifest_tree="$($aapt2 dump xmltree "$apk" --file AndroidManifest.xml)"

forbidden_permissions="$(printf '%s\n' "$permissions" | grep -E \
  "^uses-permission: name='(android\.permission\.INTERNET|android\.permission\.ACCESS_NETWORK_STATE|android\.permission\.READ_MEDIA_[^']+|android\.permission\.READ_EXTERNAL_STORAGE|android\.permission\.MANAGE_EXTERNAL_STORAGE|android\.permission\.READ_CONTACTS|android\.permission\.READ_SMS|android\.permission\.BIND_NOTIFICATION_LISTENER_SERVICE|android\.permission\.BIND_ACCESSIBILITY_SERVICE|android\.permission\.CAMERA)'" || true)"
if [[ -n "$forbidden_permissions" ]]; then
  echo "forbidden release permissions:" >&2
  printf '%s\n' "$forbidden_permissions" >&2
  exit 1
fi

grep -Fq "package: name='org.openlife'" <<<"$badging" || {
  echo "unexpected APK package identity" >&2
  exit 1
}
grep -Fq "launchable-activity: name='org.openlife.app.MainActivity'" <<<"$badging" || {
  echo "launcher activity is missing from APK badging" >&2
  exit 1
}
grep -Eq 'android:allowBackup\([^)]*\)=false' <<<"$manifest_tree" || {
  echo "release APK does not set allowBackup=false" >&2
  exit 1
}

exported_components="$(printf '%s\n' "$manifest_tree" | awk '
function indent(line) {
  match(line, /[^ ]/)
  return RSTART - 1
}
function emit() {
  if (in_component && exported == "true" && name != "") print name
}
/^[ ]+E: (activity|activity-alias|service|receiver|provider)([ ]|$)/ {
  emit()
  in_component = 1
  component_indent = indent($0)
  name = ""
  exported = ""
  next
}
{
  line_indent = indent($0)
  if (in_component && $0 ~ /^[ ]+E:/ && line_indent <= component_indent) {
    emit()
    in_component = 0
    name = ""
    exported = ""
  }
  if (in_component && line_indent == component_indent + 2 && $0 ~ /android:name[^=]*="[^"]+"/) {
    value = $0
    sub(/^.*android:name[^=]*="/, "", value)
    sub(/".*$/, "", value)
    name = value
  }
  if (in_component && line_indent == component_indent + 2 && $0 ~ /android:exported[^=]*=true/) exported = "true"
}
END { emit() }
')"
actual_components="$(printf '%s\n' "$exported_components" | sed '/^$/d' | sort -u)"
expected_components=$'org.openlife.app.MainActivity\norg.openlife.app.intake.IntakeActivity'
if [[ "$actual_components" != "$expected_components" ]]; then
  echo "unexpected exported components in release APK" >&2
  echo "expected:" >&2
  printf '%s\n' "$expected_components" >&2
  echo "actual:" >&2
  printf '%s\n' "$actual_components" >&2
  exit 1
fi

apk_size_bytes="$(stat -c '%s' "$apk")"
printf 'APK_PATH=%s\n' "$apk"
printf 'APK_SIZE_BYTES=%s\n' "$apk_size_bytes"
printf 'APK_PACKAGE=org.openlife\n'
printf 'EXPORTED_COMPONENTS=%s\n' "${actual_components//$'\n'/,}"
printf 'BOUNDARY_RESULT=pass\n'
