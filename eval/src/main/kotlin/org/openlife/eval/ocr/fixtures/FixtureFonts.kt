package org.openlife.eval.ocr.fixtures

import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.FontEntry
import java.awt.Font
import java.io.File

/**
 * Fonts come from files in this repository, never from the host's system or
 * logical fonts, so rendering does not depend on what is installed.
 *
 * - IBM Plex Sans, Sans SemiBold and Mono (OFL-1.1): the app's own vendored
 *   files. They are Latin-only subsets.
 * - Liberation Sans 2.1.5 (OFL-1.1), vendored under eval/fonts for the
 *   Cyrillic and Greek glyphs only. It never ships.
 */
class FixtureFonts private constructor(
    val sans: Font,
    val sansSemibold: Font,
    val mono: Font,
    val fallback: Font,
    val entries: List<FontEntry>,
) {
    companion object {
        const val SANS = "app/src/main/res/font/ibm_plex_sans_regular.ttf"
        const val SANS_SEMIBOLD = "app/src/main/res/font/ibm_plex_sans_semibold.ttf"
        const val MONO = "app/src/main/res/font/ibm_plex_mono_regular.ttf"
        const val FALLBACK = "eval/fonts/LiberationSans-Regular.ttf"

        fun load(repoRoot: File): FixtureFonts {
            val paths = listOf(SANS, SANS_SEMIBOLD, MONO, FALLBACK)
            val fonts = paths.map { Font.createFont(Font.TRUETYPE_FONT, File(repoRoot, it)) }
            return FixtureFonts(
                sans = fonts[0],
                sansSemibold = fonts[1],
                mono = fonts[2],
                fallback = fonts[3],
                entries = paths.map { FontEntry(it, Digests.sha256(File(repoRoot, it).readBytes())) },
            )
        }
    }
}
