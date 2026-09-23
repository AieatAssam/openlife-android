# OpenLife screen gallery and identity review

P0-09 uses the fixed paper/ink/vermilion identity from
`docs/design/VISUAL_IDENTITY.md`. The app keeps Material 3 navigation and
semantics, but disables dynamic colour so the ledger remains recognisable.

## Reusable pieces

- `StampBadge` is limited to `Confirmed`, `Verified`, `Stale`, and `Saved`.
  Unreviewed values have no stamp. Every stamp has a visible label and a
  semantic description.
- `FoldedCornerCard` carries the 12 dp folded-paper corner used by the mark.
- `PerforationDivider` is a dashed rule with a semantic description, not a
  colour-only separator.

The list uses folded source records and a `SAVED` stamp for records that are
actually saved. The viewer stamps an authenticated saved copy as `VERIFIED`;
unverified or corrupt content remains unstamped. Intake shows `SAVED` only in
the successful terminal state. Delete confirmation uses an outlined error
action and keeps the no-undo explanation inside a folded card.

## Core flow (P1-17)

- **List.** The top bar carries the title and a "More options" menu that
  leads to Settings and About. The one primary action is a labelled extended
  FAB, "Import from photos". Rows set the import timestamp in Plex Mono.
- **Empty list.** The empty state is a Fraunces heading ("Nothing kept yet"),
  one sentence and one filled "Import from photos" button, with no FAB.
  While the list is loading or the vault is unavailable, no import action is
  offered.
- **Viewer.** Sections run in this order: the image, then extracted text,
  then details.
  - The image area takes the source's displayed aspect ratio and is capped at
    60% of the window height. It replaces the fixed 320 dp box, so the image
    fills its box and the evidence outline covers the drawn pixels.
  - Delete opens the same named, no-undo confirmation as the list.
  - Details show "Imported: <time>" once, with the time in Plex Mono.
- **Settings and About** each have a top app bar with Back. Settings'
  heading moved from Fraunces to the Sans top-bar title, because Fraunces is
  reserved for display text.
- **Intake Saved.** Shows the `SAVED` stamp and a Done button, and still
  returns on its own after 5 s.

## Type and colour review

IBM Plex Sans is the UI family, IBM Plex Mono is the data/stamp family, and
Fraunces is used only for display and first-run identity. The body scale stays
at 16 sp / 24 sp and the existing large-font tests remain in the suite.

`PaletteContrastTest.everyBriefPairMeetsWcagAa` computes the WCAG ratio for
the semantic foreground/background pairs without a library. Attention uses a
label and a dedicated high-contrast container; evidence uses a labeled region
outline and fill, never colour alone.

## Motion review

There are exactly two branded hero moments: the `SAVED` stamp after save and
the `VERIFIED` stamp in the viewer. Both use the 160 ms 1.0 → 0.96 → 1.0
sequence. Compose's `MotionDurationScale` is honored by the animation
coroutine, and the component's reduced-motion contract snaps to the final
state when the scale is zero. No sound or haptics were added.

## Screenshot evidence

The Compose test captures a fixed 360 × 640 pixel surface and compares the
three components plus list, viewer, and first-run screens in light and dark
themes to the committed PNGs under
`app/src/androidTest/assets/golden/<render-profile>/`. The comparator allows
at most 0.5% of pixels to differ. Goldens contain only synthetic content.

A golden is valid only on the render profile that produced it:
`api<SDK>-<Build.PRODUCT>-<density>dpi`. Text antialiasing and the GPU path
(host GLES locally, SwiftShader on CI) differ enough that goldens made on the
local `dev36` emulator failed on both CI legs by 15% of pixels (runs
35800443855 and 35839602476). When no goldens exist for the running
profile the test raises an assumption failure naming the profile; Gradle
treats it as skipped, not passed, and it is recorded as a gap.

Generate goldens on a device with `bash scripts/ci/generate-goldens.sh`, or on
CI by dispatching the `CI` workflow with `generate-goldens: true`; the
instrumented-results artefact then contains
`app/src/androidTest/assets/golden/<profile>/`. Review the PNGs before
committing them.

Local evidence on the API 36 `dev36` emulator:

```text
BrandScreenshotInstrumentedTest: 1 test, 0 failures
```

The mark listing PNG was exported from the source SVG with:

```bash
nix-shell -p librsvg --run \
  'rsvg-convert -w 512 -h 512 art/icon/openlife-mark.svg \
  -o fastlane/metadata/android/en-US/images/icon.png'
```

The bundled fonts and the complete SIL Open Font License 1.1 text are present
in the app resource and in `docs/generated/THIRD_PARTY_LICENSES.md`; the About
screen exposes the same license text on-device.

## Installed design-skill review

The installed Material 3 Expressive checks were applied as follows:

- one restrained expressive treatment (paper fold/stamp) with standard M3
  navigation and 48 dp actions;
- two, and only two, hero moments;
- no dynamic colour because the repository identity explicitly fixes the
  palette;
- reduced motion, 2× font scale, TalkBack labels, and AA contrast retained.

The installed colour-system checks were applied as follows:

- brand, neutral, attention, evidence, and error roles are separate;
- all state changes have text labels in addition to colour;
- body and action pairs are checked in the JVM test;
- no UI colour literal remains outside the theme package.
