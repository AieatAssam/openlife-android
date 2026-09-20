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
    outline = Rule,
    outlineVariant = Rule,
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
    outline = Color(0xFF777168),
    outlineVariant = DarkRule,
)
