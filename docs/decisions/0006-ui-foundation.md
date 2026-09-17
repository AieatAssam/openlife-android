# ADR-0006: Fixed UI foundation and navigation boundary

Status: accepted for P0-07

## Context

P0-07 replaces the hand-rolled screen state with a production Android UI
foundation while preserving the vault and intake boundaries. The visual
identity requires a stable paper/ink/vermilion palette, while Material 3's
default dynamic-colour behaviour would vary the product and its screenshots by
device wallpaper.

## Decision

- `OpenLifeTheme` uses static light and dark Material 3 colour schemes. Dynamic
  colour is deliberately disabled. The P0-07 type scale uses the system font
  with the visual identity's measurements; IBM Plex and Fraunces are bundled
  by P0-09.
- Both activities call `enableEdgeToEdge()`, use the shared `FLAG_SECURE`
  policy, and use scaffold or safe-drawing insets. The intake activity remains
  a separate `noHistory` boundary.
- Navigation Compose owns `List`, `Viewer(sourceId)`, `Settings`, and `About`
  destinations. Routes are `@Serializable` objects/classes and carry only the
  source UUID string; no bytes, URIs, paths, or provider metadata enter saved
  navigation state. Nested back navigation pops to the list and predictive
  back is enabled in the manifest.
- User-visible copy and accessibility descriptions live in `strings.xml`.
  The JVM source scan has an empty allowlist for literal text passed to UI
  text/content-description parameters.
- The launcher is an adaptive vector icon with folded paper and keyhole
  layers, and Android 12+ uses `Theme.OpenLife.Starting` with
  `androidx.core:core-splashscreen`. The splash contains no application
  content.

The implementation adds Navigation Compose 2.10.0, Core Splashscreen 1.2.0,
and Kotlin serialization 1.11.0. These are Apache-2.0 dependencies already
covered by the repository allowlist; Gradle SHA-256 verification metadata and
the generated runtime licence inventory were updated with the resolved graph.
No third-party UI library or `material-icons-extended` was added.

## Consequences

The fixed palette is predictable and reviewable across devices, at the cost of
not following a user's wallpaper palette. Navigation state is now explicit and
type checked, but settings/about remain placeholders until their authorised
plan steps land. Font family fidelity remains intentionally deferred to P0-09.
Connected API-36 and hosted API-29 evidence remain verification obligations;
local device unavailability is recorded as a gap rather than inferred as a
pass.

## Verification

The P0-07 record is [verification/P0-07-ui-foundation.md](../verification/P0-07-ui-foundation.md).
The static secure-window control is also covered by
`SecureWindowTest`; TalkBack and hosted API-29 execution remain open items in
the C0 matrix.
