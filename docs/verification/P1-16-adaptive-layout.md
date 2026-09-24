# P1-16 verification: adaptive layout, multi-window and low memory

Date: 2026-09-24. Branch: `plan/P1-16-adaptive-layout`. Device: `dev36`
(API 36; the AVD reports totalMem 2,067,742,720 bytes, about 2 GB).
API 29 evidence comes from CI, where both emulator legs run with
`ram-size: 2048M` (see `.github/workflows/ci.yml`).

## RED (e53c2c7)

| Test | Result on the unchanged tree |
| --- | --- |
| `TrimMemoryPolicyTest.uiHiddenAndRunningLowClearSensitiveCaches` (JVM) | failed: RUNNING_CRITICAL did not clear |
| `AdaptiveLayoutTest.expandedWidthShowsTwoPanes` | failed: no detail-pane placeholder |
| `AdaptiveLayoutTest.aboutWarnsOnLowRamDevice` | failed: no notice |
| `AdaptiveLayoutTest.compactWidthOpensTheViewerInPlaceOfTheList` | guard, passed |
| `MultiWindowScrubTest.startedNotResumedKeepsContentAndCreatedScrubs` | guard, passed |

The multi-window guard has a mutation proof. Scrubbing in `onPause` instead of
`onStop` failed it with "losing focus must not scrub expected:<0> but was:<1>".
The mutation was reverted.

`LargeImportTest` is verification for R4, not RED. Its first fixture was too
small (12,184,857 bytes, under the 14 MiB floor), so the fixture was resized.

## GREEN (c095907)

- **Size class.** `OpenLifeNavHost` computes the width class with
  `androidx.window`'s `WindowSizeClass.BREAKPOINTS_V1` from its own
  `BoxWithConstraints`. That gives the size of the space it actually has,
  which is right in split-screen and under forced-size tests. `window-core`
  1.5.0 was already resolved transitively and is now declared explicitly.
- **Expanded width.** The List route becomes a 360 dp list pane, a divider,
  and a detail pane showing either the selected item's viewer or a
  placeholder.
  - A Viewer deep link becomes the selection.
  - When the window shrinks, a selection continues as the full-screen viewer.
  - Back clears the selection first.
- **Deep links.** Because the NavHost now sets its graph during layout,
  `MainActivity` waits for the first back-stack entry before handling a deep
  link.
  - The first GREEN run caught this: the viewer did not open from
    `EXTRA_OPEN_SOURCE_ID`.
- **Trim.** The trim policy also clears at RUNNING_CRITICAL. `OcrViewModel`
  registers a trim clearer that drops extracted text (Ready) and keeps run
  state.
- **Low RAM.** About shows the 2 GB RAM floor when
  `ActivityManager.isLowRamDevice` is true.

## Verification

| Command | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | pass (`TrimMemoryPolicyTest`, existing `SensitiveContentCacheTest`) |
| `AdaptiveLayoutTest` (connected) | 3/3 |
| `MultiWindowScrubTest` (connected) | 1/1 |
| `LargeImportTest` (connected, dev36 about 2 GB) | pass: `bytes=16138325 totalMem=2067742720 memoryClass=192` |
| `./gradlew :app:connectedDebugAndroidTest` | 93/93 |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL |

### Manual trim signals

```
$ am start; I understand; Not now
Status: ok
text="Nothing kept yet"
text="Import a screenshot, receipt or letter you want to keep. OpenLife saves its own encrypted copy on this device only. You can also share an image to OpenLife from another app."
text="Import from photos"
$ adb shell am send-trim-memory org.openlife RUNNING_LOW
exit=0
$ pidof org.openlife: 7316
$ adb shell am send-trim-memory org.openlife RUNNING_CRITICAL
exit=0
$ pidof org.openlife: 7316
text="Nothing kept yet"
text="Import a screenshot, receipt or letter you want to keep. OpenLife saves its own encrypted copy on this device only. You can also share an image to OpenLife from another app."
text="Import from photos"
$ logcat -b crash (openlife)
(end)
```

## Observations and limits

- **An unexplained failing run.** One `MultiWindowScrubTest` run during GREEN
  showed the app-lock "can't verify" screen instead of the viewer. That can
  happen only if the lock state was LOCKED, and no `app_lock` preferences
  existed on the fresh install.
  - A System UI "isn't responding" dialog was on screen around that time and
    was dismissed with Wait.
  - The next run passed, and so did the full suite (93/93).
  - The cause is not established; it is recorded here rather than assumed away.
- **Foldable hinge not handled.** No posture handling was added; a hinge in
  the middle of the window is treated as a plain expanded width.
- **Two-pane on real hardware.** It has not been run on a tablet or foldable.
  `AdaptiveLayoutTest` forces a 1280x800 dp configuration instead.
