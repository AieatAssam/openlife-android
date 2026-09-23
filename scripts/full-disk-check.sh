#!/usr/bin/env bash
set -euo pipefail

# This check deliberately requires an explicit smoke command. The command
# must assert the storage-unavailable UI/result and return zero; this harness
# only controls the device's temporary filler file and its cleanup.
adb_bin="${ADB:-adb}"
filler="/data/local/tmp/openlife-full-disk-check-${BASHPID}"
target_free_kib=4096
smoke_command="${OPENLIFE_FULL_DISK_SMOKE_COMMAND:-}"

gap() {
    printf 'FULL_DISK_CHECK=gap: %s\n' "$1"
    exit 2
}

cleanup() {
    "$adb_bin" shell "rm -f '$filler'" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

command -v "$adb_bin" >/dev/null 2>&1 || gap "adb is not installed"
"$adb_bin" get-state >/dev/null 2>&1 || gap "no connected adb device"
[[ -n "$smoke_command" ]] || gap "set OPENLIFE_FULL_DISK_SMOKE_COMMAND to an import smoke command"

free_kib() {
    "$adb_bin" shell df -k /data | tr -d '\r' | awk 'NR > 1 { print $4; exit }'
}

free="$(free_kib)"
[[ "$free" =~ ^[0-9]+$ ]] || gap "could not read free /data space"
printf 'DATA_FREE_KIB_BEFORE=%s\n' "$free"

while (( free > target_free_kib )); do
    request_kib=$((free - target_free_kib))
    request_bytes=$((request_kib * 1024))
    if ! "$adb_bin" shell "fallocate -l $request_bytes '$filler'" >/dev/null 2>&1; then
        # Leave filesystem metadata headroom if the first exact request is too
        # large for fallocate to satisfy in one operation.
        request_bytes=$((request_bytes * 3 / 4))
        (( request_bytes >= 1024 * 1024 )) || gap "fallocate could not reserve the remaining space"
        "$adb_bin" shell "fallocate -l $request_bytes '$filler'" >/dev/null 2>&1 ||
            gap "fallocate failed on the connected device"
    fi
    next_free="$(free_kib)"
    [[ "$next_free" =~ ^[0-9]+$ ]] || gap "could not re-read free /data space"
    (( next_free < free )) || gap "filler did not reduce free /data space"
    free="$next_free"
done

printf 'DATA_FREE_KIB_FILLED=%s\n' "$free"
printf 'RUNNING_FULL_DISK_SMOKE\n'
bash -c "$smoke_command"
printf 'FULL_DISK_CHECK=pass\n'
