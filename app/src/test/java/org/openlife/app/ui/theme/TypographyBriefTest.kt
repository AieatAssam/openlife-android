package org.openlife.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.R
import java.nio.file.Files
import java.nio.file.Path

/**
 * A-04 (P1-19-R9): the type roles follow VISUAL_IDENTITY.md. Plex Sans for
 * buttons, titles and headlines; Fraunces only for the display roles; every
 * weight a role asks for is a registered face, so nothing is synthesised.
 */
class TypographyBriefTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val type = OpenLifeTypography
    private val baseline = Typography()

    @Test
    fun buttonsTitlesAndHeadlinesUsePlexAndFrauncesOnlyForDisplay() {
        val plex = setOf(R.font.ibm_plex_sans_regular, R.font.ibm_plex_sans_medium, R.font.ibm_plex_sans_semibold)
        val sansRoles = mapOf(
            "labelLarge" to type.labelLarge,
            "titleLarge" to type.titleLarge,
            "titleMedium" to type.titleMedium,
            "titleSmall" to type.titleSmall,
            "headlineLarge" to type.headlineLarge,
            "headlineMedium" to type.headlineMedium,
            "headlineSmall" to type.headlineSmall,
            "bodyLarge" to type.bodyLarge,
            "bodyMedium" to type.bodyMedium,
            "bodySmall" to type.bodySmall,
        )
        sansRoles.forEach { (name, style) ->
            assertEquals("$name is IBM Plex Sans", plex, resourceIds(name, style))
        }
        listOf("headlineLarge" to type.headlineLarge, "headlineMedium" to type.headlineMedium, "headlineSmall" to type.headlineSmall)
            .forEach { (name, style) -> assertEquals("$name is Plex 600", FontWeight.SemiBold, style.fontWeight) }

        listOf("displayLarge" to type.displayLarge, "displayMedium" to type.displayMedium, "displaySmall" to type.displaySmall)
            .forEach { (name, style) ->
                assertEquals("$name is Fraunces", setOf(R.font.fraunces_variable), resourceIds(name, style))
                assertEquals("$name is Fraunces 500", FontWeight.Medium, style.fontWeight)
                val face = (style.fontFamily as FontListFontFamily).fonts.single() as ResourceFont
                val settings = face.variationSettings.settings.associate { it.axisName to it.toVariationValue(null) }
                assertEquals("$name carries the WONK axis", 1f, settings["WONK"])
                assertEquals("$name sets the wght axis it claims", 500f, settings["wght"])
            }
    }

    @Test
    fun everyRoleIsSetAndAsksOnlyForRegisteredWeights() {
        roles().forEach { (name, style) ->
            assertFalse("$name is left at the Material default", style == roles(baseline).getValue(name))
            val family = style.fontFamily
            assertNotNull("$name names a family", family)
            assertTrue("$name is not a generic platform family ($family)", family is FontListFontFamily)
            val weights = (family as FontListFontFamily).fonts.map { it.weight }.toSet()
            val asked = style.fontWeight ?: FontWeight.Normal
            assertTrue("$name asks for $asked; registered $weights", asked in weights)
        }
    }

    @Test
    fun ocrSpanTextUsesABodyRoleAtSixteenSp() {
        val panel = Files.readString(projectDir.resolve("app/src/main/java/org/openlife/app/ui/OcrPanel.kt"))
        val spanText = Regex("""Text\(value,\s*style\s*=\s*MaterialTheme\.typography\.(\w+)""")
            .find(panel)
        assertNotNull("the span text call is found", spanText)
        val role = spanText!!.groupValues[1]
        assertTrue("span text uses a body role, not $role", role.startsWith("body"))
        assertTrue("$role is at least 16 sp", roles().getValue(role).fontSize.value >= 16f)
    }

    private fun resourceIds(name: String, style: TextStyle): Set<Int> {
        val family: FontFamily? = style.fontFamily
        assertTrue("$name has a font list family, not $family", family is FontListFontFamily)
        return (family as FontListFontFamily).fonts.map { (it as ResourceFont).resId }.toSet()
    }

    private fun roles(typography: Typography = type): Map<String, TextStyle> = mapOf(
        "displayLarge" to typography.displayLarge,
        "displayMedium" to typography.displayMedium,
        "displaySmall" to typography.displaySmall,
        "headlineLarge" to typography.headlineLarge,
        "headlineMedium" to typography.headlineMedium,
        "headlineSmall" to typography.headlineSmall,
        "titleLarge" to typography.titleLarge,
        "titleMedium" to typography.titleMedium,
        "titleSmall" to typography.titleSmall,
        "bodyLarge" to typography.bodyLarge,
        "bodyMedium" to typography.bodyMedium,
        "bodySmall" to typography.bodySmall,
        "labelLarge" to typography.labelLarge,
        "labelMedium" to typography.labelMedium,
        "labelSmall" to typography.labelSmall,
    )
}
