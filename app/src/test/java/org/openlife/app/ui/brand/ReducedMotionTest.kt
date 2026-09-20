package org.openlife.app.ui.brand

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun stampAppearsInstantlyWhenAnimationScaleIsZero() {
        val component = projectDir.resolve(
            "app/src/main/java/org/openlife/app/ui/brand/BrandComponents.kt",
        )
        assertTrue("brand components are missing", Files.isRegularFile(component))
        val source = Files.readString(component)
        assertTrue("stamp motion must read the system duration scale", source.contains("MotionDurationScale"))
        assertTrue("zero animation scale must skip the tween", source.contains("scaleFactor == 0f"))
        assertTrue("the saved stamp must have exactly one hero motion", source.contains("160"))
    }
}
