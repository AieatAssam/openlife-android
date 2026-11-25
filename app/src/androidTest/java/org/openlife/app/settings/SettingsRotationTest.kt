package org.openlife.app.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.FirstRunPreferences

/**
 * A-11 (P1-19-R15, suspected): MainActivity handles no configuration
 * change itself, so rotation recreates it. Settings kept the Verify-all
 * pass and the reset confirmation stage in remember and
 * rememberCoroutineScope, which recreation discards. ActivityScenario.recreate
 * performs the same destroy-and-recreate as a rotation. The test never
 * confirms the reset.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRotationTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        FirstRunPreferences.setAcknowledged(instrumentation.targetContext)
    }

    @Test
    fun verifyAllAndResetStageSurviveRotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openSettings()

            // Verify all: the pass is started, then the activity is recreated.
            device.wait(Until.findObject(By.text("Verify all items")), WAIT_MS)!!.click()
            scenario.recreate()
            assertTrue(
                "the Verify-all result was lost across recreation",
                device.wait(Until.hasObject(By.textContains("verified")), WAIT_MS),
            )

            // Reset, stage 1: the typed word and the open dialog survive.
            device.wait(Until.findObject(By.text("Reset vault")), WAIT_MS)!!.click()
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT_MS)!!.text = "RES"
            scenario.recreate()
            assertTrue(
                "the type-the-word stage was lost across recreation",
                device.wait(Until.hasObject(By.text("Reset this vault?")), WAIT_MS),
            )
            assertTrue("the typed word was lost", device.hasObject(By.text("RES")))

            // Reset, stage 2: the final confirmation survives.
            device.findObject(By.clazz("android.widget.EditText")).text = "RESET"
            device.wait(Until.findObject(By.text("Continue to reset")), WAIT_MS)!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("Confirm vault deletion")), WAIT_MS))
            scenario.recreate()
            assertTrue(
                "the final confirmation stage was lost across recreation",
                device.wait(Until.hasObject(By.text("Confirm vault deletion")), WAIT_MS),
            )
            device.findObject(By.text("Cancel")).click()
            assertTrue(device.wait(Until.gone(By.text("Confirm vault deletion")), WAIT_MS))
        }
    }

    private fun openSettings() {
        device.wait(Until.findObject(By.desc("More options")), WAIT_MS)!!.click()
        device.wait(Until.findObject(By.text("Settings")), WAIT_MS)!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("Verify all items")), WAIT_MS))
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
