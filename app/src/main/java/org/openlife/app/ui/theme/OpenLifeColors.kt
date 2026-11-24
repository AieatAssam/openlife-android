package org.openlife.app.ui.theme

import androidx.compose.ui.graphics.Color

internal val Paper = Color(0xFFF6F1E7)
internal val RaisedPaper = Color(0xFFFFFCF5)
internal val Ink = Color(0xFF1B1A17)
internal val MutedInk = Color(0xFF5B5750)
internal val Rule = Color(0xFFD9D1C0)
internal val Vermilion = Color(0xFFC8412B)
internal val DarkVermilion = Color(0xFFE0654F)
internal val VermilionContainer = Color(0xFFF9DDD6)
internal val VermilionDarkContainer = Color(0xFF4A1F17)
internal val StampGreen = Color(0xFF2F6B4F)
internal val StampGreenDark = Color(0xFF7FC39B)
internal val Attention = Color(0xFFB7791F)
internal val AttentionContainer = Color(0xFFFFE7B0)
internal val DarkAttention = Color(0xFFE3B04B)
internal val DarkAttentionContainer = Color(0xFF4A320F)
internal val Highlighter = Color(0xFFFFE45C)
internal val HighlighterDark = Color(0xFFD9B93A)
internal val ErrorInk = Color(0xFF8F1D1D)
internal val ErrorInkDark = Color(0xFFF2B8B5)
internal val DarkSurface = Color(0xFF15140F)
internal val DarkRaisedSurface = Color(0xFF1E1C16)
internal val DarkInk = Color(0xFFEFE9DC)
internal val DarkMutedInk = Color(0xFFA8A297)
internal val DarkRule = Color(0xFF2E2B23)

/**
 * P1-19-R11 (A-08): the outline role for meaningful boundaries (text-field
 * and outlined-button borders). Rule (1.35:1 on paper) stays for decorative
 * dividers as outlineVariant; Outline meets 3:1 on every paper surface.
 */
internal val Outline = Color(0xFF7D776B)
internal val DarkOutline = Color(0xFF857F75)

// P1-19-R8: the paper and carbon surface steps for Material's container roles,
// lightest to darkest in light and darkest to lightest in dark.
internal val PaperLow = Color(0xFFFAF6EE)
internal val PaperContainer = Color(0xFFF3EDE1)
internal val PaperHigh = Color(0xFFEFE8DA)
internal val PaperHighest = Color(0xFFE9E1D0)
internal val PaperDim = Color(0xFFE3DACA)
internal val CarbonLowest = Color(0xFF0F0E0B)
internal val CarbonLow = Color(0xFF1A1914)
internal val CarbonHigh = Color(0xFF26241D)
internal val CarbonBright = Color(0xFF34312A)
internal val VermilionFixedDim = Color(0xFFF2C3B8)
internal val VermilionOnFixedVariant = Color(0xFF7A2718)
internal val StampGreenFixed = Color(0xFFD4E8DB)
internal val StampGreenFixedDim = Color(0xFFB8DCC5)
internal val StampGreenOnFixedVariant = Color(0xFF185133)

data class OpenLifeBrandColors(
    val paper: Color,
    val raisedPaper: Color,
    val ink: Color,
    val mutedInk: Color,
    val rule: Color,
    val fold: Color,
    val attention: Color,
    val attentionContainer: Color,
    val highlighter: Color,
)

val LocalOpenLifeBrandColors = androidx.compose.runtime.staticCompositionLocalOf {
    OpenLifeBrandColors(
        paper = Paper,
        raisedPaper = RaisedPaper,
        ink = Ink,
        mutedInk = MutedInk,
        rule = Rule,
        fold = Color(0xFFE9E1D0),
        attention = Attention,
        attentionContainer = AttentionContainer,
        highlighter = Highlighter,
    )
}

val OpenLifeLightColors = androidx.compose.material3.lightColorScheme(
    primary = Vermilion,
    onPrimary = RaisedPaper,
    primaryContainer = VermilionContainer,
    onPrimaryContainer = Ink,
    secondary = MutedInk,
    onSecondary = Paper,
    secondaryContainer = Rule,
    onSecondaryContainer = Ink,
    tertiary = StampGreen,
    onTertiary = Paper,
    tertiaryContainer = Color(0xFFD4E8DB),
    onTertiaryContainer = Ink,
    error = ErrorInk,
    onError = Paper,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = ErrorInk,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = RaisedPaper,
    onSurfaceVariant = MutedInk,
    outline = Outline,
    outlineVariant = Rule,
    surfaceTint = Vermilion,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = DarkVermilion,
    scrim = Ink,
    surfaceBright = RaisedPaper,
    surfaceDim = PaperDim,
    surfaceContainerLowest = RaisedPaper,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperHigh,
    surfaceContainerHighest = PaperHighest,
    primaryFixed = VermilionContainer,
    primaryFixedDim = VermilionFixedDim,
    onPrimaryFixed = Ink,
    onPrimaryFixedVariant = VermilionOnFixedVariant,
    secondaryFixed = Rule,
    secondaryFixedDim = PaperDim,
    onSecondaryFixed = Ink,
    onSecondaryFixedVariant = MutedInk,
    tertiaryFixed = StampGreenFixed,
    tertiaryFixedDim = StampGreenFixedDim,
    onTertiaryFixed = Ink,
    onTertiaryFixedVariant = StampGreenOnFixedVariant,
)

val OpenLifeDarkColors = androidx.compose.material3.darkColorScheme(
    primary = DarkVermilion,
    onPrimary = DarkSurface,
    primaryContainer = VermilionDarkContainer,
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = DarkMutedInk,
    onSecondary = Ink,
    secondaryContainer = DarkRule,
    onSecondaryContainer = DarkInk,
    tertiary = StampGreenDark,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF185133),
    onTertiaryContainer = Color(0xFFB8F0C9),
    error = ErrorInkDark,
    onError = Ink,
    errorContainer = Color(0xFF6B2926),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DarkSurface,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkRaisedSurface,
    onSurfaceVariant = DarkMutedInk,
    outline = DarkOutline,
    outlineVariant = DarkRule,
    surfaceTint = DarkVermilion,
    inverseSurface = DarkInk,
    inverseOnSurface = Ink,
    inversePrimary = Vermilion,
    scrim = CarbonLowest,
    surfaceBright = CarbonBright,
    surfaceDim = DarkSurface,
    surfaceContainerLowest = CarbonLowest,
    surfaceContainerLow = CarbonLow,
    surfaceContainer = DarkRaisedSurface,
    surfaceContainerHigh = CarbonHigh,
    surfaceContainerHighest = DarkRule,
    primaryFixed = VermilionContainer,
    primaryFixedDim = VermilionFixedDim,
    onPrimaryFixed = Ink,
    onPrimaryFixedVariant = VermilionOnFixedVariant,
    secondaryFixed = Rule,
    secondaryFixedDim = PaperDim,
    onSecondaryFixed = Ink,
    onSecondaryFixedVariant = MutedInk,
    tertiaryFixed = StampGreenFixed,
    tertiaryFixedDim = StampGreenFixedDim,
    onTertiaryFixed = Ink,
    onTertiaryFixedVariant = StampGreenOnFixedVariant,
)
