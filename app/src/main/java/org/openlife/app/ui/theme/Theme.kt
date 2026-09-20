package org.openlife.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Fixed OpenLife identity: wallpaper/dynamic colour is intentionally disabled. */
@Composable
fun OpenLifeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val brandColors = if (darkTheme) {
        OpenLifeBrandColors(
            paper = DarkSurface,
            raisedPaper = DarkRaisedSurface,
            ink = DarkInk,
            mutedInk = DarkMutedInk,
            rule = DarkRule,
            fold = Color(0xFF2E2B23),
            attention = DarkAttention,
            attentionContainer = DarkAttentionContainer,
            highlighter = HighlighterDark,
        )
    } else {
        LocalOpenLifeBrandColors.current
    }
    CompositionLocalProvider(LocalOpenLifeBrandColors provides brandColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) OpenLifeDarkColors else OpenLifeLightColors,
            typography = OpenLifeTypography,
            shapes = OpenLifeShapes,
            content = content,
        )
    }
}
