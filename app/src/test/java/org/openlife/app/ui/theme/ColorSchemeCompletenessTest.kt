package org.openlife.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-04 (P1-19-R8): every Material 3 colour role comes from the OpenLife
 * palette. A role left at the library default renders Material's baseline
 * purple/grey tone somewhere (menus, sheets, snackbars, the scrim).
 */
class ColorSchemeCompletenessTest {
    @Test
    fun noRoleFallsBackToMaterialBaseline() {
        val light = baselineMatches(OpenLifeLightColors, lightColorScheme())
        val dark = baselineMatches(OpenLifeDarkColors, darkColorScheme())
        assertTrue("light roles equal to the Material baseline: $light", light.isEmpty())
        assertTrue("dark roles equal to the Material baseline: $dark", dark.isEmpty())
    }

    @Test
    fun theRoleListIsTheWholeColorScheme() {
        // Guards the reflection itself: a renamed getter must not silently shrink the check.
        assertTrue("roles found: ${roles().size}", roles().size >= EXPECTED_MINIMUM_ROLES)
    }

    private fun baselineMatches(scheme: ColorScheme, baseline: ColorScheme): List<String> =
        roles().filter { getter -> getter.invoke(scheme) == getter.invoke(baseline) }
            .map { it.name.removePrefix("get").substringBefore('-') }

    private fun roles() = ColorScheme::class.java.methods.filter { method ->
        method.name.startsWith("get") && method.parameterCount == 0 && method.returnType == java.lang.Long.TYPE
    }

    private companion object {
        // ColorScheme in material3 1.4.0 has 48 colour roles (javap, P1-19).
        const val EXPECTED_MINIMUM_ROLES = 48
    }
}
