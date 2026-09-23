package org.openlife.app.ui

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.MainActivity
import org.openlife.app.intake.IntakeActivity

@RunWith(AndroidJUnit4::class)
class SecureWindowTest {
    @get:Rule
    val strictMode = StrictModeRule()


    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @Test
    fun mainActivityAppliesFlagSecureAfterCreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "MainActivity must set FLAG_SECURE",
                    (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
                )
            }
        }
    }

    @Test
    fun intakeActivityAppliesFlagSecureAfterCreate() {
        ActivityScenario.launch(IntakeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "IntakeActivity must set FLAG_SECURE",
                    (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
                )
            }
        }
    }
}
