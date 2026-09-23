# P1-17 verification: core UX and flow pass

Date: 2026-09-23. Branch: `plan/P0-10-ci-green-and-core-ux`. Device: `dev36`
(API 36, google_apis x86_64, host GLES). No API 29 device or physical device
was available.

## RED (eedfcb6)

`CoreFlowUxTest` has 11 tests. On the unchanged screens 10 failed for the
intended reasons:
- the viewer delete fired straight away ("expected:<0> but was:<1>")
- the empty-state heading and the labelled import action were missing
- there was no "More options" menu, and no Back on Settings or About
- extracted text sat below Details (top 709 dp vs 468 dp)
- the image-area tag was missing
- the Saved screen had no Done

`loadingListHasNoImportAction` passed from the start as a guard. The old
top-bar "+" exposed only a content description, never a text label.

## GREEN (cc1cd09) and REFACTOR

| Command | Result |
| --- | --- |
| `./gradlew detekt lint :app:test :vault:test assembleDebug` | BUILD SUCCESSFUL |
| `CoreFlowUxTest` at 1080x2400 / 420 dpi | 11/11 |
| `CoreFlowUxTest` at `wm size 320x640`, `wm density 160` | 11/11 |
| `./gradlew :app:connectedDebugAndroidTest` (after goldens regenerated) | 58 tests, 0 failures |
| `./gradlew :vault:connectedDebugAndroidTest` | 82 tests, 0 failures |

Before the goldens were regenerated, the app suite ran 57/58. The one failure
was the intended change to `list-light.png` for the local render profile
(26,592 px). Only the `list-*` goldens changed. At 420 dpi the 360x640 px
viewer capture shows only the top bar, so it is pixel-identical.

## On-device walk-through (uiautomator, fresh install)

```text
FIRST-RUN: "Before you import anything" ... "I understand"
LIST:      "Nothing kept yet" / "Import a screenshot, receipt or letter ..." /
           "Import from photos" / "OpenLife" / desc "More options"
MENU:      "Settings" "About"
ABOUT:     "OpenLife" ... "Vault access failure counts: none recorded" / desc "Back" / "About"
SETTINGS:  "Reset vault" / desc "Back" / "Settings"
Back  ->   list again
```

The in-app Back buttons on About and Settings returned to the list. Neither
the system back gesture nor a share intent was needed.

## Visual review

I captured full-size light and dark screens (empty list, populated list,
viewer with synthetic OCR, Settings, Saved) with a temporary Compose capture
harness. The harness was not committed. The review found one deviation: the
FAB used M3's tonal primary container. It is now filled vermilion, as
VISUAL_IDENTITY §7 requires.

## Gaps

- CI-profile goldens for API 29 and API 36 are still to be generated (P0-10
  mechanism). Until then, CI reports the brand screenshot test as an
  assumption skip.
- No TalkBack walk-through was done; P1-10 owns that.
- Evidence geometry for rotated sources is still P2-06's job. The image area
  already swaps axes for quarter turns, but the overlay mapping is unchanged.
