package org.openlife.app.ui.brand

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

class PaletteContrastTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun everyBriefPairMeetsWcagAa() {
        val source = Files.readString(
            projectDir.resolve("app/src/main/java/org/openlife/app/ui/theme/OpenLifeColors.kt"),
        )
        val colors = Regex("(?:internal val|val)\\s+(\\w+)\\s*=\\s*Color\\(0xFF([0-9A-Fa-f]{6})\\)")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2].toLong(16) }
        val requiredPairs = listOf(
            "RaisedPaper" to "Vermilion",
            "Ink" to "VermilionContainer",
            "Paper" to "StampGreen",
            "Ink" to "AttentionContainer",
            "ErrorInk" to "Paper",
            "DarkSurface" to "DarkVermilion",
            "DarkInk" to "DarkAttentionContainer",
        )

        requiredPairs.forEach { (foreground, background) ->
            val foregroundColor = colors[foreground]
                ?: error("Missing foreground token $foreground")
            val backgroundColor = colors[background]
                ?: error("Missing background token $background")
            val contrast = contrastRatio(foregroundColor, backgroundColor)
            assertTrue(
                "$foreground on $background has contrast $contrast",
                contrast >= 4.5,
            )
        }
    }

    private fun contrastRatio(first: Long, second: Long): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val encoded = ((color shr shift) and 0xFF) / 255.0
            return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun Double.pow(exponent: Double): Double = this.powInternal(exponent)

    private fun Double.powInternal(exponent: Double): Double = Math.pow(this, exponent)

    /**
     * A-08 (P1-19-R11): the outline role marks component boundaries (text field
     * borders, outlined buttons), so it needs 3:1 against the surfaces it sits on
     * (WCAG 1.4.11). Rule stays as outlineVariant for decorative dividers.
     */
    @Test
    fun outlineRoleMeetsThreeToOneOnPaperAndCarbon() {
        val light = org.openlife.app.ui.theme.OpenLifeLightColors
        val dark = org.openlife.app.ui.theme.OpenLifeDarkColors
        listOf(
            "light outline on background" to (light.outline to light.background),
            "light outline on surface" to (light.outline to light.surface),
            "light outline on surfaceContainerHigh" to (light.outline to light.surfaceContainerHigh),
            "dark outline on background" to (dark.outline to dark.background),
            "dark outline on surface" to (dark.outline to dark.surface),
            "dark outline on surfaceContainerHigh" to (dark.outline to dark.surfaceContainerHigh),
        ).forEach { (name, pair) ->
            val ratio = ratio(pair.first.toArgb(), pair.second.toArgb())
            assertTrue("$name is $ratio:1", ratio >= 3.0)
        }
        assertTrue("decorative dividers keep Rule", light.outlineVariant == org.openlife.app.ui.theme.Rule)
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Long =
        ((alpha * 255).toLong() shl 24) or ((red * 255 + 0.5f).toLong() shl 16) or
            ((green * 255 + 0.5f).toLong() shl 8) or (blue * 255 + 0.5f).toLong()

    private fun ratio(first: Long, second: Long): Double {
        fun luminance(color: Long): Double {
            fun channel(shift: Int): Double {
                val encoded = ((color shr shift) and 0xFF) / 255.0
                return if (encoded <= 0.04045) encoded / 12.92 else Math.pow((encoded + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
