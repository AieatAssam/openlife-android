# Runbook: real process kill during deletion (C0-14, P1-15-R5)

Purpose: show that a real `kill -9` while a large item is being deleted
never produces a "Deleted" item that still has files, a resurrected item, or
an orphaned artefact. The automated test
`RecoveryRepositoryTest.deletionInterruptedAfterMarkingIsResumed` simulates
the interruption in process. This runbook uses a real kill.

Content is synthetic only: the debug build's `TestHostileContentProvider`
generates a 4000x3000 noise JPEG on demand. Use a debug build on an emulator
or test device, never a device with real user data.

## Steps

1. Install the debug build, launch OpenLife, and acknowledge first run.

   ```bash
   ./gradlew :app:installDebug
   adb shell am start -n org.openlife/.app.MainActivity
   ```

2. Import the large synthetic fixture and tap **Save**.

   ```bash
   adb shell am start -a android.intent.action.SEND -t image/jpeg \
     --eu android.intent.extra.STREAM \
     content://net.openlifetest.hostileprovider/large-fault-injection.jpg \
     -n org.openlife/.app.intake.IntakeActivity
   ```

3. Open the saved item, tap **Delete**, and confirm. Immediately (within
   about 100 ms) kill the process:

   ```bash
   adb shell am kill org.openlife || adb shell kill -9 "$(adb shell pidof org.openlife)"
   ```

   Repeat the kill with different delays (0, 50, 200 ms) to land on
   different checkpoints: before `DELETING` commits, between file removals,
   and before the row transaction.

4. Relaunch OpenLife.

   ```bash
   adb shell am start -n org.openlife/.app.MainActivity
   ```

## Expected results

- The item is either still present and openable, because the kill landed
  before `DELETING` was committed, or it is gone.
- It is never shown as saved with a missing image.
- It never shows "Deleted" and then reappears.
- After startup recovery, `adb shell run-as org.openlife ls
  no_backup/vault/artefacts` lists no `.blob` or `.stage` file for a deleted
  item.
- Logcat (debug build) shows an `OpenLifeRecovery` report whose
  `resumedDeletions` count includes the interrupted deletion when the kill
  landed after `DELETING`.

## Record

Record each run (device, API, delay, and which outcome occurred) in
`docs/verification/C0.md`, row C0-14. This runbook was written for P1-15 on
2026-09-23 and was not executed that day. The shared build host was
overloaded (load average above 70) and the emulator had been OOM-killed
earlier. Until it is run, the real-kill leg is a gap.
