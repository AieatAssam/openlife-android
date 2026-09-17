package org.openlife.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Fixed OpenLife identity: wallpaper/dynamic colour is intentionally disabled. */
@Composable
fun OpenLifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) OpenLifeDarkColors else OpenLifeLightColors,
        typography = OpenLifeTypography,
        shapes = OpenLifeShapes,
        content = content,
    )
}
