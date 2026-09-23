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
}
