# OpenLife visual identity and interface design brief

Version 1.0 — 2026-09-16. Governs the look of the app from plan step P0-07/P0-09
on. Product rules in `openlife-design-v0.3.md` win on conflict; this brief
never justifies hiding a limit or softening a warning.

## 1. What the identity must say

OpenLife is a private ledger of things you chose to keep. The interface should
feel like **well-made paper records kept in a locked drawer**: calm, tactile,
precise, slightly old-fashioned in its honesty. It must not look like a
generic AI product: no purple-to-blue gradients, no glowing orbs, no
glassmorphism, no sparkle icons, no stock "assistant" chat bubbles, no
mascots, no rounded-blob illustration style, no lorem-ipsum-grade copy.

Three words to check every screen against: **kept, evidenced, honest**.

## 2. Name and wordmark

- Name: `OpenLife`, one word, capital O and L.
- Wordmark: set in **Fraunces** (OFL), optical size 72, weight 500, "WONK" axis
  on, letter-spacing −1%. Used only on the first-run screen, About, and the
  store listing. Never as a floating header on content screens.

## 3. Mark and launcher icon

Concept: **a paper stub with a folded corner and a keyhole cut out of it**.
The stub says "kept record"; the keyhole says "locked, yours". No lock body,
no shield, no cloud, no eye.

- Geometry (adaptive icon, 108 dp canvas, safe zone 66 dp): cream paper
  rectangle 52×64 dp centred, 3 dp corner radius, top-right corner folded
  (fold triangle 14 dp, drawn as a darker cream face); keyhole (circle 9 dp +
  wedge 7 dp tall) centred at 60% height, cut out to show the vermilion
  background.
- Foreground: paper `#F6F1E7` with ink outline `#1B1A17` at 2 dp; fold face
  `#E9E1D0`. Background layer: vermilion `#C8412B` flat. Monochrome layer:
  the stub silhouette with the keyhole as a hole.
- Source: `art/icon/openlife-mark.svg` (committed by P0-09), exported to
  `mipmap-anydpi-v26` vectors. No raster icons except the Play/F-Droid 512 px
  PNG derived from the SVG.

## 4. Colour

Fixed palette. **Dynamic colour is off** (a recognisable identity beats
Material You's per-device recolouring; this amends ADR-0006). Contrast targets
are WCAG AA at minimum; text on paper is AAA.

| Role | Light ("paper") | Dark ("carbon") | Use |
| --- | --- | --- | --- |
| Surface | `#F6F1E7` | `#15140F` | app background |
| Surface raised | `#FFFCF5` | `#1E1C16` | cards, sheets |
| Ink (on surface) | `#1B1A17` | `#EFE9DC` | body text, icons |
| Ink muted | `#5B5750` | `#A8A297` | secondary text, timestamps |
| Rule | `#D9D1C0` | `#2E2B23` | dividers, perforation dashes |
| Vermilion (primary) | `#C8412B` | `#E0654F` | primary buttons, the icon background, "Delete" is NOT vermilion |
| Vermilion container | `#F9DDD6` | `#4A1F17` | primary container, selected chips |
| Stamp green (confirmed) | `#2F6B4F` | `#7FC39B` | Confirmed / Verified badges only |
| Highlighter (evidence) | `#FFE45C` | `#D9B93A` | evidence region outline and span highlight |
| Amber (attention) | `#B7791F` | `#E3B04B` | stale, needs clarification, not assessed |
| Error | `#8F1D1D` | `#F2B8B5` | destructive confirmations, unreadable content |

Rules: vermilion is the only brand colour on a screen; green appears only on
confirmed or verified states so it keeps meaning; amber marks uncertainty;
never use colour alone (every state has a label). Delete actions use the
error colour and an outlined button, never the primary filled button.

Material 3 mapping (P0-07 theme): primary=Vermilion, onPrimary=Surface,
primaryContainer=Vermilion container, secondary=Ink muted, tertiary=Stamp
green, surface/onSurface=Surface/Ink, outline=Rule, error=Error.

## 5. Typography (all OFL, bundled, licences in About)

- **IBM Plex Sans** — UI text. Body 16 sp/24, label 14 sp, title 22 sp/28
  weight 600, headline 28 sp/34 weight 600.
- **IBM Plex Mono** — everything that is *data*: extracted text spans,
  digests, timestamps, amounts, dates, file sizes. Data looks like data; it
  is visibly distinct from the app's own words.
- **Fraunces** — display only: wordmark, first-run headline, empty-state
  headings. Never in lists or dialogs.
- Minimum body size 16 sp; supports font scale to 2.0×; no text in images.

## 6. Shape, texture, motion

- Corner radius 4 dp on cards and sheets ("cut paper"), 8 dp on buttons,
  full radius only on chips. No 24–28 dp blobs.
- Source cards carry a small folded top-right corner (12 dp) drawn in code, the
  same motif as the icon. Sections separate with a dashed 1 dp "perforation"
  rule instead of solid dividers.
- Badges for Confirmed/Verified are drawn as **stamps**: 1.5 dp outline,
  uppercase Plex Mono 12 sp, rotated −3°, stamp green. Unreviewed values have
  no stamp. Stale values get an amber "STALE" stamp with a strike-through of
  the old value, never deletion.
- Evidence highlight: highlighter-yellow outline 3 dp with a 30% fill,
  square corners, on the letterboxed image rect (P2-06 geometry).
- Motion: two hero moments per the Material 3 Expressive guidance and no
  more. (1) Save: the preview card "stamps" — a 160 ms scale 1.0→0.96→1.0
  with the SAVED stamp appearing; (2) Confirm a fact: the stamp appears with
  the same motion. Everything else uses standard M3 transitions. Honour the
  system animation scale; at 0 the stamp appears instantly.
- No sound, no haptics beyond the platform's default click.

## 7. Layout and content rules

- One primary action per screen, filled vermilion, bottom-right or bottom
  bar; secondary actions outlined. Destructive actions outlined error.
- Lists are dense ledgers: 56 dp thumbnail, Plex Sans label, Plex Mono
  timestamp, state stamp. No cards inside cards.
- Empty states are typographic: a Fraunces heading, one sentence in Plex
  Sans, one action. No illustration.
- Copy is plain, second person, no exclamation marks, no "magic", no
  "AI-powered". Limits are stated where they apply ("There is no backup").
  Extracted text is always labelled as extracted and untrusted.
- Screens work at Compact, Medium and Expanded width; list–detail pairs at
  Expanded (P1-16).

## 8. Accessibility is part of the identity

Contrast AA everywhere, AAA for body text on paper; 48 dp targets; every
stamp and colour state has a text label; TalkBack order follows reading
order; evidence regions are focusable nodes describing the span.

## 9. Deliverables (plan step P0-09)

`art/icon/openlife-mark.svg`, `art/wordmark.svg`, Compose theme tokens
(`ui/theme/OpenLifeColors.kt`, `Type.kt`, `Shapes.kt`), bundled fonts under
`res/font/` with OFL texts, `StampBadge`, `FoldedCornerCard`,
`PerforationDivider` composables with previews and screenshot tests, and a
`docs/design/screens.md` gallery of uiautomator-verified screens.
