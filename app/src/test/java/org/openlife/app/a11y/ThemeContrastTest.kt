package org.openlife.app.a11y

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.boundary.KotlinSource
import org.openlife.app.ui.brand.StampState
import org.openlife.app.ui.brand.stampColors
import org.openlife.app.ui.theme.OpenLifeBrandColors
import org.openlife.app.ui.theme.OpenLifeDarkBrandColors
import org.openlife.app.ui.theme.OpenLifeDarkColors
import org.openlife.app.ui.theme.OpenLifeLightBrandColors
import org.openlife.app.ui.theme.OpenLifeLightColors
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * P1-10: WCAG 2.2 contrast for every foreground/background pair the theme
 * defines, light and dark. Text needs 4.5:1 (1.4.3; the app's smallest text
 * is 12 sp, so nothing qualifies as large text); outlines and markers need
 * 3:1 (1.4.11). The pairs are enumerated, not hand-picked:
 * - every Material `onX` role against `X`, found by reflection over [ColorScheme];
 * - every Material content role against every Material surface role;
 * - every [OpenLifeBrandColors] property, by reflection, against the surfaces
 *   the app draws brand colours on;
 * - every [StampState] label and outline.
 * Every Material role and every brand property must be classified, and every
 * colour role app code names must be one the classification covers, so a new
 * role or a new use cannot slip past unchecked.
 *
 * What this cannot prove: colours composited at runtime (alpha, tonal
 * elevation, ripples), text drawn over the user's image (the evidence
 * outline), disabled controls (exempt under WCAG 1.4.3), and which surface a
 * colour is actually drawn on. The device runbook covers those.
 */
class ThemeContrastTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val themes = listOf(
        Theme("light", OpenLifeLightColors, OpenLifeLightBrandColors),
        Theme("dark", OpenLifeDarkColors, OpenLifeDarkBrandColors),
    )

    @Test
    fun everyMaterialRoleIsClassified() {
        val roles = materialRoles(OpenLifeLightColors).keys
        assertTrue("reflection found ${roles.size} roles", roles.size >= MINIMUM_MATERIAL_ROLES)
        val unclassified = roles.filter { classify(it, roles) == null }
        assertTrue("Material roles with no contrast rule: $unclassified", unclassified.isEmpty())
    }

    @Test
    fun everyOnRoleReachesTextContrastOnItsContainer() {
        val failures = themes.flatMap { theme ->
            val roles = materialRoles(theme.scheme)
            onPairs(roles.keys).map { (foreground, background) ->
                Check("${theme.name} $foreground on $background", roles.getValue(foreground), roles.getValue(background))
            }.mapNotNull { it.failure(TEXT) }
        }
        assertTrue("on-role pairs below 4.5:1: $failures", failures.isEmpty())
    }

    @Test
    fun contentRolesReachContrastOnEverySurface() {
        val failures = themes.flatMap { theme ->
            val roles = materialRoles(theme.scheme)
            val surfaces = roles.keys.filter(::isSurface)
            surfaces.flatMap { surface ->
                TEXT_ROLES.map { role ->
                    Check("${theme.name} $role on $surface", roles.getValue(role), roles.getValue(surface)) to TEXT
                } + NON_TEXT_ROLES.map { role ->
                    Check("${theme.name} $role on $surface", roles.getValue(role), roles.getValue(surface)) to NON_TEXT
                }
            }.mapNotNull { (check, minimum) -> check.failure(minimum) }
        }
        assertTrue("content roles below their minimum: $failures", failures.isEmpty())
    }

    @Test
    fun brandColoursReachContrastOnTheSurfacesTheyAreDrawnOn() {
        val properties = brandProperties(OpenLifeLightBrandColors).keys
        val unclassified = properties - BRAND_TEXT - BRAND_NON_TEXT - BRAND_SURFACES - BRAND_EXEMPT.keys
        assertTrue("brand colours with no contrast rule: $unclassified", unclassified.isEmpty())
        val failures = themes.flatMap { theme ->
            val brand = brandProperties(theme.brand)
            val surfaces = brandSurfaces(theme)
            surfaces.flatMap { (surface, background) ->
                BRAND_TEXT.map { Check("${theme.name} $it on $surface", brand.getValue(it), background) to TEXT } +
                    BRAND_NON_TEXT.map { Check("${theme.name} $it on $surface", brand.getValue(it), background) to NON_TEXT }
            }.mapNotNull { (check, minimum) -> check.failure(minimum) } +
                // Search draws matched words in Ink on the highlighter (SearchScreen.highlighted).
                listOfNotNull(Check("${theme.name} Ink on highlighter", INK, theme.brand.highlighter).failure(TEXT))
        }
        assertTrue("brand colours below their minimum: $failures", failures.isEmpty())
    }

    @Test
    fun everyStampLabelAndOutlineReachesContrast() {
        val failures = themes.flatMap { theme ->
            StampState.entries.flatMap { state ->
                val colors = stampColors(state, theme.scheme, theme.brand)
                brandSurfaces(theme).flatMap { (surface, background) ->
                    listOfNotNull(
                        Check("${theme.name} $state label on $surface", colors.label, background).failure(TEXT),
                        Check("${theme.name} $state outline on $surface", colors.border, background).failure(NON_TEXT),
                    )
                }
            }
        }
        assertTrue("stamps below their minimum: $failures", failures.isEmpty())
    }

    /** Every colour app code names is one this test classifies; new uses must be added here first. */
    @Test
    fun everyColourRoleAppCodeUsesIsCovered() {
        val sources = appSources()
        val materialUses = sources.flatMap { MATERIAL_USE.findAll(it).map { match -> match.groupValues[1] }.toList() }
        val brandUses = sources.flatMap { BRAND_USE.findAll(it).map { match -> match.groupValues[1] }.toList() }
        val constantUses = sources.flatMap { THEME_CONSTANT_IMPORT.findAll(it).map { match -> match.groupValues[1] }.toList() }
        val roles = materialRoles(OpenLifeLightColors).keys
        // An on-role is checked on its own container (everyOnRoleReachesTextContrastOnItsContainer).
        val onRoles = onPairs(roles).map { it.first }
        val coveredMaterial = roles.filter(::isSurface) + TEXT_ROLES + NON_TEXT_ROLES + onRoles + MATERIAL_EXEMPT.keys
        val brand = brandProperties(OpenLifeLightBrandColors).keys
        val uncovered = materialUses.filter { it in roles && it !in coveredMaterial } +
            brandUses.filter { it in brand && it !in BRAND_TEXT + BRAND_NON_TEXT + BRAND_SURFACES + BRAND_EXEMPT.keys } +
            constantUses.filter { it in COLOR_CONSTANTS && it !in CONSTANT_USES }
        assertTrue("colour uses not covered by a contrast rule: ${uncovered.distinct()}", uncovered.isEmpty())
        assertTrue("self-check: the scan sees app colour uses", materialUses.contains("error") && brandUses.isNotEmpty())
        // Brand colours are checked on page and card surfaces only; none is used inside a dialog or menu.
        val inDialogs = KotlinSource.app(projectDir).flatMap { source ->
            (source.calls("AlertDialog") + source.calls("DropdownMenu"))
                .filter { BRAND_USE.containsMatchIn(it.all) }
                .map { it.location() }
        }
        assertTrue("brand colours inside a dialog or menu: $inDialogs", inDialogs.isEmpty())
    }

    @Test
    fun theRatioMatchesTheWcagReferenceValues() {
        assertClose(21.0, ratio(Color.Black, Color.White))
        assertClose(1.0, ratio(Color.White, Color.White))
        // #777777 on white is the textbook just-below-AA grey (4.48:1).
        assertClose(4.48, ratio(Color(0xFF777777), Color.White))
    }

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue("expected $expected, was $actual", kotlin.math.abs(expected - actual) < RATIO_TOLERANCE)

    private fun classify(role: String, roles: Set<String>): String? = when {
        isSurface(role) -> "surface"
        role in TEXT_ROLES -> "text"
        role in NON_TEXT_ROLES -> "non-text"
        role in MATERIAL_EXEMPT -> "exempt: ${MATERIAL_EXEMPT[role]}"
        onPairs(roles).any { (foreground, background) -> role == foreground || role == background } -> "on-pair"
        else -> null
    }

    private fun onPairs(roles: Set<String>): List<Pair<String, String>> = roles.filter { it.startsWith("on") }
        .flatMap { foreground ->
            val base = foreground.removePrefix("on").replaceFirstChar(Char::lowercase)
            when {
                base.endsWith("FixedVariant") -> base.removeSuffix("Variant").let { listOf(it, "${it}Dim") }
                base.endsWith("Fixed") -> listOf(base, "${base}Dim")
                else -> listOf(base)
            }.filter { it in roles }.map { foreground to it }
        } + listOf("inverseOnSurface" to "inverseSurface", "inversePrimary" to "inverseSurface").filter { (a, b) ->
            a in roles && b in roles
        }

    private fun isSurface(role: String) = role == "background" || SURFACE_ROLE.matches(role)

    private fun brandSurfaces(theme: Theme): List<Pair<String, Color>> {
        val roles = materialRoles(theme.scheme)
        return CONTENT_SURFACES.map { it to roles.getValue(it) } +
            BRAND_SURFACES.map { it to brandProperties(theme.brand).getValue(it) }
    }

    private fun appSources(): List<String> {
        val root = projectDir.resolve("app/src/main/java/org/openlife/app")
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !it.startsWith(root.resolve("ui/theme")) }
                .map { Files.readString(it) }
                .collect(Collectors.toList())
        }
    }

    private class Theme(val name: String, val scheme: ColorScheme, val brand: OpenLifeBrandColors)

    private class Check(val label: String, val foreground: Color, val background: Color) {
        fun failure(minimum: Double): String? {
            val value = ratio(foreground, background)
            return if (value < minimum) "$label is ${"%.2f".format(value)}:1 (needs $minimum)" else null
        }
    }

    private companion object {
        const val TEXT = 4.5
        const val NON_TEXT = 3.0
        const val RATIO_TOLERANCE = 0.01
        const val MINIMUM_MATERIAL_ROLES = 48
        val INK = Color(0xFF1B1A17)
        val SURFACE_ROLE = Regex("surface(Bright|Dim|Variant|Container\\w*)?")

        /** Material roles app text and icons are drawn in. */
        val TEXT_ROLES = listOf("onSurface", "onSurfaceVariant", "onBackground", "primary", "secondary", "tertiary", "error")

        /** Boundaries a user must perceive: text-field and outlined-button borders. */
        val NON_TEXT_ROLES = listOf("outline")

        /** Roles with no contrast rule, each with the reason. */
        val MATERIAL_EXEMPT = mapOf(
            "outlineVariant" to "decorative dividers only (WCAG 1.4.11 exempts decoration)",
            "surfaceTint" to "tonal-elevation tint; every surface here has 0 dp tonal elevation",
            "scrim" to "dims the window behind a dialog; nothing is read on it",
        )

        /** Surfaces app content (cards, rows, stamps, notices) is drawn on. */
        val CONTENT_SURFACES = listOf("background", "surface", "surfaceVariant")

        val BRAND_TEXT = setOf("ink", "mutedInk")
        val BRAND_NON_TEXT = setOf("attention")
        val BRAND_SURFACES = setOf("paper", "raisedPaper")
        val BRAND_EXEMPT = mapOf(
            "rule" to "perforation and fold lines are decorative (WCAG 1.4.11)",
            "fold" to "the folded-corner face is decorative",
            "attentionContainer" to "a container with its own on-colour (ink), checked by the on-pair rule in PaletteContrastTest",
            "highlighter" to "drawn over the user's image (contrast unknowable) or behind Ink text (checked below)",
        )

        /** Theme constants app code imports directly, and why each is covered. */
        val CONSTANT_USES = setOf("Ink")
        val COLOR_CONSTANTS by lazy {
            Regex("""val\s+(\w+)\s*=\s*Color\(""").findAll(
                Files.readString(
                    Path.of(System.getProperty("openlife.projectDir", "."))
                        .resolve("app/src/main/java/org/openlife/app/ui/theme/OpenLifeColors.kt"),
                ),
            ).map { it.groupValues[1] }.toSet()
        }

        val MATERIAL_USE = Regex("""colorScheme\.(\w+)""")
        val BRAND_USE = Regex("""(?:LocalOpenLifeBrandColors\.current|\bbrand)\.(\w+)""")
        val THEME_CONSTANT_IMPORT = Regex("""import org\.openlife\.app\.ui\.theme\.(\w+)""")

        fun materialRoles(scheme: ColorScheme): Map<String, Color> = ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
            .associate { method ->
                method.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercase) to
                    Color((method.invoke(scheme) as Long).toULong())
            }

        fun brandProperties(brand: OpenLifeBrandColors): Map<String, Color> = OpenLifeBrandColors::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
            .associate { method ->
                method.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercase) to
                    Color((method.invoke(brand) as Long).toULong())
            }

        fun ratio(first: Color, second: Color): Double {
            val a = luminance(first)
            val b = luminance(second)
            return (max(a, b) + LUMINANCE_OFFSET) / (min(a, b) + LUMINANCE_OFFSET)
        }

        private const val LUMINANCE_OFFSET = 0.05

        private fun luminance(color: Color): Double {
            fun channel(value: Float): Double {
                val encoded = (value * 255f).let { kotlin.math.round(it) } / 255.0
                return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
    }
}
