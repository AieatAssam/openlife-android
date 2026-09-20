package org.openlife.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.openlife.app.R

private val OpenLifeSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)
private val OpenLifeMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))
private val OpenLifeDisplay = FontFamily(Font(R.font.fraunces_variable, FontWeight.Normal))

val OpenLifeTypography = Typography(
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeSans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeSans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeSans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeMono,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = OpenLifeMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
