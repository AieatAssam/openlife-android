package org.openlife.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.openlife.app.R

private val OpenLifeSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)
internal val OpenLifeMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))

/**
 * Fraunces for display roles only (VISUAL_IDENTITY "Type"): weight 500 with
 * the WONK axis on and optical size 72, set on the variable font's axes so
 * nothing is synthesised (P1-19-R9). The font's axes: opsz 9-144, wght
 * 100-900, SOFT 0-100, WONK 0-1.
 */
@OptIn(ExperimentalTextApi::class)
private val OpenLifeDisplay = FontFamily(
    Font(
        R.font.fraunces_variable,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FRAUNCES_DISPLAY_WEIGHT),
            FontVariation.Setting("WONK", 1f),
            FontVariation.Setting("opsz", FRAUNCES_OPTICAL_SIZE),
        ),
    ),
)

private const val FRAUNCES_DISPLAY_WEIGHT = 500
private const val FRAUNCES_OPTICAL_SIZE = 72f

private fun plex(weight: FontWeight, size: Int, line: Int) = TextStyle(
    fontFamily = OpenLifeSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun display(size: Int, line: Int) = TextStyle(
    fontFamily = OpenLifeDisplay,
    fontWeight = FontWeight.Medium,
    fontSize = size.sp,
    lineHeight = line.sp,
)

/**
 * Plex Sans for UI text (titles, headlines, buttons), Plex Mono for data
 * labels, Fraunces only for the display roles (wordmark, first-run and
 * empty-state headings); dialogs use headlineSmall, so they stay Plex.
 */
val OpenLifeTypography = Typography(
    displayLarge = display(45, 52),
    displayMedium = display(36, 44),
    displaySmall = display(28, 34),
    headlineLarge = plex(FontWeight.SemiBold, 28, 34),
    headlineMedium = plex(FontWeight.SemiBold, 24, 32),
    headlineSmall = plex(FontWeight.SemiBold, 22, 28),
    titleLarge = plex(FontWeight.SemiBold, 22, 28),
    titleMedium = plex(FontWeight.Medium, 16, 24),
    titleSmall = plex(FontWeight.Medium, 14, 20),
    bodyLarge = plex(FontWeight.Normal, 16, 24),
    bodyMedium = plex(FontWeight.Normal, 16, 24),
    bodySmall = plex(FontWeight.Normal, 16, 24),
    labelLarge = plex(FontWeight.Medium, 14, 20),
    labelMedium = TextStyle(fontFamily = OpenLifeMono, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = OpenLifeMono, fontSize = 12.sp, lineHeight = 16.sp),
)
