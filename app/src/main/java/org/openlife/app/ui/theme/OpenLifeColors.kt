package org.openlife.app.ui.theme

import androidx.compose.ui.graphics.Color

internal val Paper = Color(0xFFF6F1E7)
internal val RaisedPaper = Color(0xFFFFFCF5)
internal val Ink = Color(0xFF1B1A17)
internal val MutedInk = Color(0xFF5B5750)
internal val Rule = Color(0xFFD9D1C0)
internal val Vermilion = Color(0xFFC8412B)
internal val VermilionDark = Color(0xFFE0654F)
internal val VermilionContainer = Color(0xFFF9DDD6)
internal val VermilionDarkContainer = Color(0xFF4A1F17)
internal val StampGreen = Color(0xFF2F6B4F)
internal val StampGreenDark = Color(0xFF7FC39B)
internal val ErrorInk = Color(0xFF8F1D1D)
internal val ErrorInkDark = Color(0xFFF2B8B5)

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
    primary = VermilionDark,
    onPrimary = Ink,
    primaryContainer = VermilionDarkContainer,
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFA8A297),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF2E2B23),
    onSecondaryContainer = Color(0xFFE5DED2),
    tertiary = StampGreenDark,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF185133),
    onTertiaryContainer = Color(0xFFB8F0C9),
    error = ErrorInkDark,
    onError = Ink,
    errorContainer = Color(0xFF6B2926),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF15140F),
    onBackground = Color(0xFFEFE9DC),
    surface = Color(0xFF15140F),
    onSurface = Color(0xFFEFE9DC),
    surfaceVariant = Color(0xFF1E1C16),
    onSurfaceVariant = Color(0xFFA8A297),
    outline = Color(0xFF777168),
    outlineVariant = Color(0xFF2E2B23),
)
