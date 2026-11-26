# Runbook: TalkBack and accessibility settings (C0-16, P1-10-R4, OA-3)

Status: **written; not yet executed.** This is an owner action (OA-3,
`plan/plan.yaml`). Nothing here has been run on a device. The automated
checks it complements are listed in
`docs/verification/P1-10-accessibility.md`.

Purpose: a person using TalkBack, switch access, a keyboard, very large text
or display size, right-to-left layout, colour inversion or high-contrast text,
or reduced motion can import, find, read, correct and delete an item, and use
settings and app lock, without sighted help. The automated checks read the
semantics tree. Only this runbook hears what TalkBack actually says.

## Rules for the run

- Use a debug build on a test device with **synthetic content only**. Never
  use a device that holds real user documents.
- Fixtures: three synthetic images with Latin text (for example a typed page
  that reads "Synthetic appointment 14 October"). The debug-only hostile
  provider (`docs/runbooks/kill-during-deletion.md`) is also fine.
- Record the device model, Android version, TalkBack version and build
  commit at the top of the result table.
- Expected speech below is the English copy in `app/src/main/res/values/strings.xml`.
  TalkBack adds its own words ("Button", "Heading", "double-tap to
  activate", list positions). These may differ by TalkBack version; fail a
  step only when the meaning is missing or wrong.
- Mark each row **P** (pass), **F** (fail: write what was said), or **N/A**
  (with the reason). Do not leave a row blank.
- Content must never reach a notification, the window title, Recents, or
  logs. Check `adb logcat -d | grep -i synthetic` after the run: it must be empty.

## Setup

1. `./gradlew :app:installDebug`, then clear data:
   `adb shell pm clear org.openlife`. If `pm clear` uninstalls the app, re-run
   `./gradlew :app:installDebug` (see `docs/verification/`).
2. Push the fixtures to the device's Pictures folder so the Photo Picker
   shows them.
3. Turn on TalkBack (Settings > Accessibility > TalkBack). Enable
   "Speak passwords" so field echo is heard. Set the speech rate to default.

## A. TalkBack: every screen and flow

Gestures: swipe right/left = next/previous item; double-tap = activate;
three-finger tap or swipe up-then-right = TalkBack menu (actions);
swipe up/down to change the reading control to "Headings".

| # | Screen / step | Do | Expected (spoken, at least) | P/F |
|---|---|---|---|---|
| A1 | First run | Launch the app | Pane "Before you import anything"; "OpenLife, Heading"; "Before you import anything, Heading" | [ ] |
| A2 | First run | Swipe through to the end | Body and device-lock text read in order, then "Continue, Button"; nothing skipped, nothing read twice | [ ] |
| A3 | First run | Headings navigation | Moves between the two headings only | [ ] |
| A4 | Lock offer | Double-tap Continue | Pane "Lock OpenLife too?"; heading read first; "Use device lock" and "Not now" buttons reachable | [ ] |
| A5 | Lock offer (no screen lock set) | Choose to enable | The "set a screen lock" refusal text is **announced without moving focus** | [ ] |
| A6 | Empty list | Choose "Not now" | Pane "OpenLife" (list title); "OpenLife, Heading"; empty-list heading; "Import from Photos, Button". The plus icon is silent | [ ] |
| A7 | Empty list | Swipe to the top-bar actions | "Search, Button" and "More options, Button" | [ ] |
| A8 | Import | Activate "Import from Photos", pick a fixture | Picker is the system's. Back in OpenLife: pane "Import an image"; "Preparing…" announced; then the preview: "Selected image" plus the format/size details; "Cancel" and "Save" buttons | [ ] |
| A9 | Import | Double-tap Save | "Saving…" then "Saved" announced **without focus moving**; the stamp "Saved"; "Done, Button" | [ ] |
| A10 | Import duplicate | Import the same fixture again | The "already saved" message is announced; "Open existing" and "Cancel" buttons | [ ] |
| A11 | Import rejected | Share a non-image (e.g. a .txt) to OpenLife | The not-imported reason is announced; "Done, Button" | [ ] |
| A12 | List | Return to the list with 3 items | Each row reads its generic label ("Imported …") and "double-tap to Open"; then a separate "Delete, Button". A damaged row says it is unavailable | [ ] |
| A13 | List | Count focus stops per row | One stop for the row and one for Delete; the thumbnail is not a separate stop | [ ] |
| A14 | Viewer | Open an item | Pane "Saved image"; heading with the item label; "Back, Button"; "Delete, Button" | [ ] |
| A15 | Viewer | Focus the image | "Saved image", state "Zoom: 100%" (read "100 percent"); the TalkBack actions menu offers "Zoom in" | [ ] |
| A16 | Viewer zoom | Actions > Zoom in, twice | State becomes "Zoom: 200%", then "Zoom: 400%"; "Zoom out" and "Fit to screen" now offered | [ ] |
| A17 | Viewer zoom | Actions > Fit to screen | "Zoom: 100%"; Zoom out/Fit no longer offered | [ ] |
| A18 | Viewer OCR | Activate "Extract text" | "Extracting…" announced; Cancel reachable; then the extracted-text heading and spans | [ ] |
| A19 | Viewer OCR | Focus a span row | Span text, then "double-tap to Show in image" | [ ] |
| A20 | Viewer OCR | Activate a span | The selected-span announcement (polite) is read once; the region on the image reads "selected" | [ ] |
| A21 | Viewer OCR | Swipe to the region on the image | Each region reads its text preview, "Button"; the selected one adds "selected"; a rejected one reads "rejected" and offers no action | [ ] |
| A22 | Viewer OCR | Activate "Correct" on a span | Dialog: title is a heading; the field reads its label once (check it is not read twice: label + description); "Save correction", "Cancel" | [ ] |
| A23 | Viewer OCR | Save a correction | Focus returns to the viewer; the corrected text and "Corrected by you" are read | [ ] |
| A24 | Viewer OCR | Reject a span, then Undo | "Rejected" state read; "Undo" restores it; nothing is re-announced | [ ] |
| A25 | Viewer OCR failure | Force a failure (airplane mode is irrelevant; use the debug fault fixture) or cancel during extraction | "Extraction cancelled" / failure text announced; "Run again" | [ ] |
| A26 | Viewer OCR history | Activate the history button | History items are read; record whether "expanded/collapsed" is spoken (known gap, see notes) | [ ] |
| A27 | Viewer details | Headings navigation | "Extracted text", "Details" headings reached | [ ] |
| A28 | Viewer delete | Activate Delete | Dialog title heading names the item; explanation; "Delete" and "Cancel"; focus starts inside the dialog | [ ] |
| A29 | Viewer delete | Cancel | Focus returns to the Delete button (or the top of the viewer) | [ ] |
| A30 | Search | Open Search | Pane "Search"; "Search, Heading"; the field reads its label; idle hint announced | [ ] |
| A31 | Search | Type "synthetic" | "Searching…" then the result count / "no results" announced; each result reads kind, item label, snippet, "double-tap to Open in viewer" | [ ] |
| A32 | Settings | Overflow > Settings | Pane "Settings"; section headings; app-lock switch reads "on/off"; timeout options read as radio buttons with "selected" | [ ] |
| A33 | Settings | Verify all | "Checking…" then the verified/damaged/unchecked counts announced | [ ] |
| A34 | Settings | Start "Reset vault", cancel at each step | Dialog titles are headings; the confirmation-word field is labelled; failure text (if forced) is announced | [ ] |
| A35 | About | Overflow > About | Pane "About"; "OpenLife, Heading"; each section a heading; each fact reads label and value as one item; privacy/licences toggles read "Show …/Hide …" | [ ] |
| A36 | App lock | Enable lock, leave the app past the timeout, return | Pane "OpenLife is locked"; heading; "Unlock, Button"; no item count or label anywhere | [ ] |
| A37 | App lock | Cancel the system prompt | "Not verified" note is announced; Unlock still reachable | [ ] |
| A38 | Vault unavailable | (Debug only) force a key failure per `docs/runbooks/` or P1-14 notes | "Vault unavailable" heading; explanation; "What this means", "Try again", "Open settings" buttons | [ ] |
| A39 | Back navigation | From each secondary screen, press system Back | TalkBack announces the previous pane title | [ ] |
| A40 | Notifications / title | During A8–A31 open the shade and Recents | No content in any notification; Recents shows no thumbnail; window title has no document text | [ ] |

## B. Switch access and keyboard

| # | Check | Expected | P/F |
|---|---|---|---|
| B1 | Switch Access (one switch, auto-scan) through first run, list, viewer | Every control in section A is reachable and activatable; no trap | [ ] |
| B2 | Switch Access on the image | The "Zoom in/out/Fit" actions are offered from the item's actions menu | [ ] |
| B3 | Hardware keyboard: Tab / Shift+Tab on every screen | Focus moves in reading order; the focus indicator is visible on every control | [ ] |
| B4 | Keyboard: Enter/Space on rows, buttons, radio options | Activates the same action as a tap | [ ] |
| B5 | Keyboard: Esc / Back in dialogs | Dismisses the dialog; focus returns to the opener | [ ] |
| B6 | Keyboard: arrow keys in a scrolling screen at 200 % text | Scrolls; focus never lands on an off-screen control without scrolling it in | [ ] |

## C. Text size, display size, layout and colour

| # | Setting | Check on every screen in section A | Expected | P/F |
|---|---|---|---|---|
| C1 | Font size: largest (200 % on Android 14+) | No clipped or overlapping text; every action reachable by scrolling; dialogs scroll | Pass on all screens | [ ] |
| C2 | Display size: largest | As C1 | As C1 | [ ] |
| C3 | C1 and C2 together | As C1 | As C1 | [ ] |
| C4 | RTL: Developer options > Force RTL layout direction | Mirrored layout; Back arrow mirrored; row label and Delete do not overlap; reading order follows RTL | [ ] |
| C5 | Colour inversion | The highlighter, stamps and region outlines stay visible; selected region still distinguishable (TalkBack "selected" and outline) | [ ] |
| C6 | High-contrast text | Text remains legible on paper and dark surfaces; the Stale stamp label legible | [ ] |
| C7 | Dark theme | Repeat A6, A14, A18 and A32 visually | Contrast holds | [ ] |
| C8 | Remove animations (Settings > Accessibility > Remove animations) | Screen changes and the FAB do not animate; nothing is lost because an animation did not run | [ ] |
| C9 | Colour correction (any mode) | No state is conveyed by colour alone: rejected spans also struck through; selection also announced | [ ] |

## Result table (paste into `docs/verification/C0.md`, C0-16 row)

```text
TalkBack runbook (docs/runbooks/talkback.md) run <date> by <who>
Device: <model>, Android <version>, TalkBack <version>, build <commit>
A1-A40: <n> P, <n> F, <n> N/A   B1-B6: <n> P, <n> F   C1-C9: <n> P, <n> F
Failures: <row: what was said / seen>
```

Until that line exists, C0-16's TalkBack leg remains a gap.
