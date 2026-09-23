# P0-06 — Minified release build and smoke verification

Status: verified (2026-09-20)

## Release size

The P0-04 release-inspection run recorded the pre-R8, unminified APK at
78,232,193 bytes. The P0-06 minified APK is 31,995,133 bytes, a 59.1%
reduction.

## R8 and native-library checks

- Full R8 mode is enabled in gradle.properties; release code and resources
  are both shrunk.
- app/proguard-rules.pro documents the SQLCipher JNI, Room generated-code,
  coroutines debug-agent, and stack-trace mapping rules. The future OCR JNI
  rule is intentionally deferred to P2-03 when the engine is selected.
- scripts/check-16kb-alignment.sh checks every packaged native library's
  LOAD alignment, explicit extractNativeLibs=false, and APK page alignment
  with zipalign -c -P 16 -v 4.
- Local minified release build: BUILD SUCCESSFUL; boundary inspection passed;
  scripts/check-16kb-alignment.sh reported 16KB_ALIGNMENT_RESULT=pass across
  six packaged native libraries.

## Hosted verification

CI run [35484905441](https://github.com/AieatAssam/openlife-android/actions/runs/35484905441)
passed all four jobs. The build/lint/unit job and release APK boundary
inspection passed. Fresh API-29 and API-36 emulator legs each passed vault
72/72 and app 38/38 connected tests, release assembly, native alignment
(`16KB_ALIGNMENT_RESULT=pass`, `NATIVE_LIB_COUNT=6`), and the complete smoke
flow. Both smoke legs reported `MEDIA_SCAN_BROADCAST_STATUS=0`,
`RELEASE_SMOKE_RESULT=pass`, `THUMBNAILS_BEFORE=0`, and
`THUMBNAILS_AFTER_DELETE=0`. The smoke flow uses the viewer's immediate Delete
action after reopen; the list-row path retains its separate confirmation
dialog.

## Smoke contract

scripts/release-smoke.sh installs the release APK on the active CI emulator,
shares a synthetic PNG through MediaStore, asserts the preview, performs
save -> reopen -> delete, and fails on an OpenLife fatal exception. When the
input is the intentionally unsigned local release output, it signs only a
temporary smoke copy with a disposable non-debug key; the original release
APK and mapping.txt are not modified.

The hosted API-29/API-36 emulator legs are the authoritative fresh-device
smoke verification. Local manual reproduction also verified the MediaStore
URI grant and prefixed integrity row; no device data was cleared or reset.
