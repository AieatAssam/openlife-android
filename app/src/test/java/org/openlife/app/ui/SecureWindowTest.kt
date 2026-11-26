package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Source tripwires for the window policy (FLAG_SECURE and, review U-01, the
 * autofill exclusion). They prove the calls are present, not that the
 * platform honours them; the instrumented AutofillExclusionTest checks the
 * hosted view tree on a device.
 */
class SecureWindowTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun everyActivityWindowHasFlagSecure() {
        val manifest = Files.readString(projectDir.resolve("app/src/main/AndroidManifest.xml"))
        assertTrue(
            "predictive back must be enabled for every activity window",
            manifest.contains("android:enableOnBackInvokedCallback=\"true\""),
        )
        val secureWindow = Files.readString(
            projectDir.resolve("app/src/main/java/org/openlife/app/ui/SecureWindow.kt"),
        )
        assertTrue(secureWindow.contains("FLAG_SECURE"))
        assertTrue(
            "every activity must apply the shared secure-window policy",
            listOf(
                "app/src/main/java/org/openlife/app/MainActivity.kt",
                "app/src/main/java/org/openlife/app/intake/IntakeActivity.kt",
                // P1-20-R5: the non-exported internal link entry.
                "app/src/main/java/org/openlife/app/intake/InternalLinkActivity.kt",
            ).all { Files.readString(projectDir.resolve(it)).contains("applySecureWindow()") },
        )
    }

    /** Review U-01: every Activity in app/src/main applies the shared policy, which excludes autofill. */
    @Test
    fun everyActivityExcludesItsWindowFromAutofill() {
        val activities = Files.walk(projectDir.resolve("app/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { ACTIVITY_CLASS.containsMatchIn(Files.readString(it)) }
                .collect(Collectors.toList())
        }
        assertEquals("activities found: $activities", 3, activities.size)
        val missing = activities.filterNot { appliesPolicy(Files.readString(it)) }
        assertTrue("activities without applySecureWindow(): $missing", missing.isEmpty())

        val secureWindow = Files.readString(projectDir.resolve("app/src/main/java/org/openlife/app/ui/SecureWindow.kt"))
        assertTrue(secureWindow.contains("IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS"))
        assertTrue(secureWindow.contains("excludeWindowFromAutofill(window.decorView)"))
        val field = Files.readString(projectDir.resolve("app/src/main/java/org/openlife/app/ui/ContentTextField.kt"))
        assertTrue("the field excludes its own (dialog) window", field.contains("excludeWindowFromAutofill(view)"))
        assertTrue("the field is not autofillable", field.contains("contentDataType = ContentDataType.None"))
        assertTrue("the field's value is stripped from fill requests", field.contains("isSensitiveData = true"))

        val mutation = "class LeakyActivity : FragmentActivity() {\n    override fun onCreate(b: Bundle?) {}\n}"
        assertTrue("self-check: an activity is recognised", ACTIVITY_CLASS.containsMatchIn(mutation))
        assertFalse("self-check: a missing call is reported", appliesPolicy(mutation))
    }

    private fun appliesPolicy(source: String): Boolean = source.contains("applySecureWindow()")

    private companion object {
        val ACTIVITY_CLASS = Regex("""\bclass\s+\w+\s*(\([^)]*\))?\s*:\s*[\w.]*Activity\s*\(""")
    }
}
