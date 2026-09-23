#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'usage: %s <release-apk>\n' "$0" >&2
    exit 2
}

[[ $# -eq 1 ]] || usage
apk="$1"
[[ -f "$apk" ]] || { printf 'release APK not found: %s\n' "$apk" >&2; exit 1; }

find_sdk_tool() {
    local tool="$1"
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
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

zipalign="$(find_tool zipalign)"
aapt2="$(find_tool aapt2)"
readelf_bin="$(command -v readelf)"
unzip_bin="$(command -v unzip)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
"$unzip_bin" -q "$apk" -d "$tmp/apk"

manifest_tree="$tmp/manifest.xmltree"
"$aapt2" dump xmltree "$apk" --file AndroidManifest.xml > "$manifest_tree"
if ! grep -Eq 'extractNativeLibs[^\n]*(0x00000000|false)' "$manifest_tree"; then
    printf 'extractNativeLibs is not explicitly false\n' >&2
    exit 1
fi

if ! "$zipalign" -c -P 16 -v 4 "$apk" > "$tmp/zipalign.log" 2>&1; then
    printf 'zipalign 16-byte-page check failed\n' >&2
    cat "$tmp/zipalign.log" >&2
    exit 1
fi

mapfile -t native_libs < <(find "$tmp/apk/lib" -type f -name '*.so' -print 2>/dev/null | sort)
native_lib_count="$(printf '%s\n' "${native_libs[@]}" | sed '/^$/d' | wc -l | tr -d '[:space:]')"
if [[ "$native_lib_count" -eq 0 ]]; then
    printf 'release APK contains no native libraries to inspect\n' >&2
    exit 1
fi

for library in "${native_libs[@]}"; do
    while IFS= read -r alignment; do
        [[ -n "$alignment" ]] || continue
        if ! [[ "$alignment" =~ ^0x[0-9a-fA-F]+$ ]]; then
            printf 'unrecognised LOAD alignment in %s: %s\n' "$library" "$alignment" >&2
            exit 1
        fi
        if (( alignment < 0x4000 )); then
            printf 'LOAD segment is below 16 KB alignment in %s: %s\n' "$library" "$alignment" >&2
            exit 1
        fi
    done < <("$readelf_bin" -lW "$library" | awk '$1 == "LOAD" { print $NF }')
done

printf '16KB_ALIGNMENT_RESULT=pass\n'
printf 'NATIVE_LIB_COUNT=%s\n' "$native_lib_count"
