# Runbook: physical-device checks (P1-11-R2)

Written: 2026-11-26. **Status: not yet executed.** No step in this runbook has
been run on any device. Every result in the table at the end is blank. The
owner executes this runbook (owner action OA-3 in `plan/plan.yaml`, which asks
for one physical device on API 29-31 and one on the current API).

What this runbook covers: install of the signed release build, share and
Photo Picker imports, reboot persistence, the backup attempts, a
device-to-device transfer check, screen and recents behaviour, and the
performance timings that `docs/verification/C0.md` lists as not measured on a
reference device.

What it does not cover: CI emulator evidence (`docs/verification/C0.md`,
C0-16), and the release-build performance procedure on the CI emulator
(`docs/runbooks/release-performance.md`).

## Preconditions

1. **Device.** One physical Android device with adb debugging enabled, with
   the serial shown by `adb devices`. Record the model and Android version
   from Settings > About phone. The device must be on API 29-31 for one run
   and on the current API for the other (OA-3).

2. **No OpenLife data on the device.** The application ID is
   `org.openlife`. In `app/build.gradle.kts` line 149 it is set as
   `applicationId = "org.openlife"` in `defaultConfig`. The only build type
   declared is `release` (line 172). No build type sets `applicationIdSuffix`,
   so a debug build and a release build install under the same package name.
   Before you start, check that the device has no OpenLife install that holds
   real data. If one exists, uninstall it, because uninstalling deletes the
   vault and the vault cannot be recovered (`docs/PRODUCT_PRINCIPLES.md`, P7).
   The step brief states that the instrumented test suite resets the vault.
   This runbook does not depend on that statement.

3. **Synthetic documents only.** Every image used in this runbook must be
   synthetic. Do not use photographs of real documents, real receipts, or
   anything from the owner's own storage. The debug-only
   `TestHostileContentProvider` (a debug source set, not in the release APK)
   is not available to a release build, so the test images must be copied to
   the device by a method you choose. Record the file names and their source
   in the results table.

4. **Signed release APK.** Build the release variant with the owner's upload
   key. `docs/runbooks/signing.md` requires the four variables
   `OPENLIFE_KEYSTORE_B64`, `OPENLIFE_KEYSTORE_PASSWORD`, `OPENLIFE_KEY_ALIAS`
   and `OPENLIFE_KEY_PASSWORD`. Without all four, the release variant is
   unsigned (`app/build.gradle.kts`, `signingConfigs`, lines 133-145). Never
   use a debug-signed APK for this runbook. The output path is
   `app/build/outputs/apk/release/*.apk` (`CLAUDE.md`, release smoke command).

5. **App lock and screen lock.** Record whether the device has a screen lock
   and whether OpenLife's app lock is on (Settings in the app). The app lock is
   off by default and is offered once after first run (`docs/decisions/0004-app-lock.md`).
   Record the setting in the results table, because it changes the expected
   results in steps 4, 5 and 9.

## Steps

Each step lists the action, the expected result, and the source for that
expectation. Status for every step: **not yet executed**.

### Step 1: record the device and the build

1. Run `adb devices` and confirm one device with the expected serial.
2. Record the model and Android version (Settings > About phone).
3. Record the commit: `git rev-parse --short HEAD` in the repository.
4. Record the APK file name and its SHA-256 digest (`sha256sum` on the APK).

Expected: one device is listed. The commit and digest are recorded.
Source: `docs/verification/C0.md`, Build identity section (the commit and
build identity fields the verification record already uses).
Status: not yet executed.

### Step 2: install the signed release APK

1. Confirm the signature before install. Use the `apksigner` in
   `$ANDROID_HOME/build-tools/<version>/`:

   ```bash
   $ANDROID_HOME/build-tools/<version>/apksigner verify --verbose --print-certs app/build/outputs/apk/release/<file>.apk
   ```

2. Install it:

   ```bash
   adb install -r app/build/outputs/apk/release/<file>.apk
   ```

Expected: `apksigner` reports the owner's upload certificate, not the debug
certificate. Its SHA-256 digest matches the value the release workflow records
in `CERTIFICATE-FINGERPRINT-SHA256.txt`. The install succeeds. If the install is
refused because an older OpenLife install with a different signature is
present, uninstall it (precondition 2) and install again. Record the refusal
text.
Source: `docs/runbooks/signing.md` (apksigner verify; certificate SHA-256).
The `adb install -r` form is the standard adb command; the step brief does
not quote a command for this step.
Status: not yet executed.

### Step 3: launch and first run

```bash
adb shell am start -n org.openlife/org.openlife.app.MainActivity
```

Expected: the launcher activity opens the source list. Only the launcher
activity and one intake activity are exported in the merged release manifest.
Source: `app/src/main/AndroidManifest.xml`, lines 33-41 (`MainActivity`,
`exported="true"`, `LAUNCHER`) and lines 52-59 (`IntakeActivity`, `SEND`,
`image/*`). `docs/verification/C0.md`, C0-15.
Status: not yet executed.

### Step 4: share-intent import (synthetic image)

1. Put one synthetic JPEG or PNG on the device (precondition 3).
2. In the device's own gallery or files app, select the image and choose
   **Share**, then choose **OpenLife**.
3. Check the preview. Tap **Save** only after reading it.
4. Open the saved item from the list.

Expected: OpenLife appears as a share target for image types. The preview
appears before anything is saved. The item is saved only after **Save**, and
then it appears in the list and opens in the viewer. Record whether the app
lock prompt appeared (precondition 5).
Source: `app/src/main/AndroidManifest.xml`, lines 52-59 (exported
`IntakeActivity`, `ACTION_SEND`, `image/*`). `docs/capabilities/C0.md` (import
by share and picker, READY only after confirmation; C0-02 in
`docs/verification/C0.md`).
Status: not yet executed.

### Step 5: Photo Picker import (synthetic image)

1. In OpenLife, start an import from inside the app (not a share).
2. The system Photo Picker opens. Select the synthetic image.
3. Read the preview, then tap **Save**.

Expected: the preview states that the picked item may come from a cloud
photo service that OpenLife does not control, and that OpenLife never
uploads. Saving works as in step 4. If the app lock is on, returning from the
picker does not relock the app on a short round trip. A trip longer than the
greater of the timeout and 60 seconds relocks it (`docs/decisions/0004-app-lock.md`).
Record the observed behaviour.
Source: `docs/THREAT_MODEL.md` (Photo Picker paragraph); `docs/PERMISSIONS.md`
(Photo Picker intake); `docs/decisions/0004-app-lock.md` (external trips).
Photo Picker UI is an open gap in `docs/verification/C0.md` (C0-02 and the
gap list), so this step is the first device evidence for it.
Status: not yet executed.

### Step 6: reboot persistence

1. Save one synthetic item (step 4) and note its title in the results table.
2. Reboot the device (`adb reboot`, or the device's own reboot control).
3. Unlock the device, and unlock OpenLife if the app lock is on.
4. Open OpenLife and find the item. Open it in the viewer.

Expected: the item is still listed and opens with its content. If the
device's screen lock was removed, OpenLife reports the vault as unavailable
(`KEY_UNWRAP_FAILED`) and keeps the data. It does not erase the vault
(`docs/THREAT_MODEL.md`, key loss). Record the observed result.
Source: `docs/verification/C0.md`, C0-08 (restart and reboot after save);
`docs/capabilities/C0.md` (survives process death and reboot);
`docs/THREAT_MODEL.md` (key loss).
Status: not yet executed.

### Step 7: backup attempt with bmgr

Run the command exactly as given in the step brief. The brief says it needs a
backup transport. The brief does not give the command that selects one, so
the owner supplies it and records it.

```bash
adb shell bmgr backupnow org.openlife
```

Expected: OpenLife is not backed up. The manifest sets `android:allowBackup="false"`
(`app/src/main/AndroidManifest.xml`, line 22). Legacy full-backup rules
exclude every domain (`app/src/main/res/xml/backup_rules.xml`, lines 9-13).
The Android 12+ cloud-backup rules exclude every domain
(`app/src/main/res/xml/data_extraction_rules.xml`, lines 12-21). The exact
text `bmgr` prints is not defined by the repository. Copy it into the results
table as observed.
Source: `app/src/main/AndroidManifest.xml` (line 22-24);
`docs/THREAT_MODEL.md`, controls section.
Status: not yet executed.

### Step 8: adb backup refusal

The step brief lists this check but does not give a command. The owner
supplies the command before this step is run, and records it in the results
table.

Expected: no OpenLife data is written to the backup output, because
`allowBackup` is `false` (`app/src/main/AndroidManifest.xml`, line 22). The
exact result (refusal message, or empty output) is recorded as observed.
Source: `app/src/main/AndroidManifest.xml`, line 22; `docs/THREAT_MODEL.md`.
Status: not yet executed.

### Step 9: device-to-device transfer (OEM tool, if available)

1. If the device maker offers a phone-to-phone transfer tool, run it from a
   second device or a computer, as the tool requires. Record the tool name and
   version.
2. Check whether OpenLife data arrives on the target device.

Expected: no OpenLife vault data is transferred. The device-transfer rules
exclude every domain (`app/src/main/res/xml/data_extraction_rules.xml`, lines
22-31). The manifest comment says manufacturer behaviour must be checked on
each device, because the rules alone do not prove it
(`app/src/main/res/xml/data_extraction_rules.xml`, lines 8-9;
`docs/verification/C0.md`, known limitations). If no OEM tool exists on the
device, write "no OEM tool" in the results table. Do not substitute another
method.
Status: not yet executed.

### Step 10: screen and recents behaviour

1. Open a saved item in the viewer.
2. Take a screenshot with the device's own screenshot control. Note the result.
3. Open the Recents screen and look at the OpenLife card.
4. Optionally dump the UI on the device:

   ```bash
   adb shell uiautomator dump
   ```

Expected: content windows carry `FLAG_SECURE`, so a screenshot of an
OpenLife content screen should be blank or black, and the OpenLife card in
Recents should show no content preview. The repository does not say what the
device's own screenshot control shows, so record what this device shows. The
threat model records the recents check as made with `dumpsys window`
(`docs/THREAT_MODEL.md`, FLAG_SECURE paragraph). `adb screencap` returns a
black frame by design (`CLAUDE.md`, sandbox notes).
Source: `docs/THREAT_MODEL.md` (FLAG_SECURE and recents); `docs/verification/C0.md`
(C0-15); `docs/capabilities/C0.md` (C0-R32); `CLAUDE.md` (uiautomator dump).
Status: not yet executed.

### Step 11: performance timings (cold and warm prepare, reopen, peak PSS)

Targets (`docs/verification/C0.md`, Performance section; design §12):
reopen a saved 4 MiB image in under 2 s; prepare it in under 5 s after the
provider starts delivering bytes. The previous timings in C0.md are
emulator-only and were taken on a debug build. Do not compare against them
as device evidence.

The measurement method is the device-local `logcat` timestamp method from
`docs/verification/C0.md`: take the timestamp at both ends of each span from
the device's own clock, not the host's. The C0.md record found about 2.1 s of
host/device clock skew and ruled it out by using device-local timestamps.

1. Prepare a synthetic image of about 4 MiB (precondition 3). Record its size.
2. **Cold prepare.** Kill the OpenLife process so the next run is a fresh
   process:

   Put OpenLife in the background first (press Home). `am kill` does not stop
   a foreground process. Then run:

   ```bash
   adb shell am kill org.openlife
   ```

   Share the image to OpenLife (as step 4). Record the span from the share
   intent to the `Prepared` state.
3. **Warm prepare.** Without killing the process, share a second copy of the
   image. Record the span.
4. **Reopen.** Tap the saved item in the list. Record the span from the tap
   to the decrypted image.
5. **Peak PSS.** After the image is on screen:

   ```bash
   adb shell dumpsys meminfo org.openlife
   ```

   Record the TOTAL PSS value.
6. Dump the device logs and keep the file for the results table:

   ```bash
   adb logcat -d
   ```

Expected: each span is compared with its target (cold and warm prepare under
5 s; reopen under 2 s). This runbook does not say what happens when a target
is missed. Threshold for peak PSS: not defined in the brief or the design
documents; owner decision.

Open question for the owner: the C0.md method uses log lines at each end of
the span. It does not name those lines, and it was written for the debug
build. Before you measure, confirm which device-local log lines exist in the
release build. If none exist, record that the measurement cannot be taken and
do not add logging to produce one.

Repetitions: not defined in the brief, owner decision. Record every
repetition, not only the best one.
Source: `docs/verification/C0.md` (Performance section; logcat and
meminfo method); `docs/runbooks/kill-during-deletion.md` (`adb shell am kill`).
Status: not yet executed.

## Results table

Fill in after execution. Leave cells blank until a step has been run.

| Field | Value |
| --- | --- |
| Device model | |
| Android version (API level) | |
| Build commit (`git rev-parse --short HEAD`) | |
| APK SHA-256 | |
| Signing certificate SHA-256 (from apksigner) | |
| App lock on or off, screen lock present | |
| Date | |
| Tester | |

| Step | Result (pass / fail / not run) | Observed output or notes |
| --- | --- | --- |
| 1. Record device and build | | |
| 2. Install signed release APK | | |
| 3. Launch and first run | | |
| 4. Share-intent import | | |
| 5. Photo Picker import | | |
| 6. Reboot persistence | | |
| 7. bmgr backupnow | | |
| 8. adb backup refusal | | |
| 9. Device-to-device transfer | | |
| 10. Screen and recents | | |
| 11a. Cold prepare, 4 MiB (ms) | | |
| 11b. Warm prepare, 4 MiB (ms) | | |
| 11c. Reopen (ms) | | |
| 11d. Peak PSS (KB) | | |
