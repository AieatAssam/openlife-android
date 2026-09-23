package org.openlife.app.ui

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.test.StrictModeRule

/**
 * P1-13-R3: the first-run explanation is shown until acknowledged, and both
 * reading and writing that flag stay off the main thread. Other suites
 * pre-acknowledge first run, so this is the only test of the tap itself.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunFlowTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearAcknowledgement() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun restoreAcknowledgement() {
        FirstRunPreferences.setAcknowledged(context)
    }

    @Test
    fun acknowledgingFirstRunShowsTheListWithoutMainThreadDiskAccess() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val understand = device.wait(Until.findObject(By.text("I understand")), WAIT_MS)
            assertTrue("first-run explanation was not shown", understand != null)
            understand.click()
            assertTrue(
                "list did not follow the acknowledgement",
                device.wait(Until.hasObject(By.desc("More options")), WAIT_MS),
            )
        }
        assertTrue("acknowledgement was not persisted", FirstRunPreferences.isAcknowledged(context))
    }

    private companion object {
        const val PREFS_NAME = "first_run"
        const val WAIT_MS = 30_000L
    }
}
