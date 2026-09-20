package org.openlife.app.ui.brand

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class StampBadgeTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun appearsOnlyForConfirmedVerifiedStaleAndSavedStates() {
        val component = projectDir.resolve(
            "app/src/main/java/org/openlife/app/ui/brand/BrandComponents.kt",
        )
        assertTrue("brand components are missing", Files.exists(component))
        val source = Files.readString(component)
        listOf("Confirmed", "Verified", "Stale", "Saved").forEach { state ->
            assertTrue("missing stamp state $state", source.contains(state))
        }
        assertTrue("unreviewed values must not receive a stamp", !source.contains("Unreviewed"))
        assertTrue("stamps must be labelled", source.contains("contentDescription"))
    }
}
