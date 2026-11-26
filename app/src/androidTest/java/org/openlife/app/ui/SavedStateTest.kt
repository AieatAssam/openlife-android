package org.openlife.app.ui

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.test.StrictModeRule

/**
 * P1-20 (search query never in saved state; routes carry ids only): with a
 * query typed into search, the activity's saved-instance bundle, walked
 * recursively, holds no trace of it.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SavedStateTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(instrumentation.targetContext)
    }

    @Test
    fun savedBundleHoldsNoContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Search your items")), WAIT_MS))
            device.findObject(By.desc("Search your items")).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS))
            device.findObject(By.clazz("android.widget.EditText")).text = QUERY
            assertTrue(device.wait(Until.hasObject(By.text(QUERY)), WAIT_MS))

            val saved = Bundle()
            scenario.onActivity { instrumentation.callActivityOnSaveInstanceState(it, saved) }

            val dump = StringBuilder().also { describe(saved, it) }.toString()
            assertTrue("the bundle was not walked", dump.isNotEmpty())
            assertFalse("the search query is in saved state", dump.contains(QUERY))
        }
    }

    private fun describe(value: Any?, out: StringBuilder) {
        when (value) {
            is Bundle -> value.keySet().forEach { key ->
                out.append(key).append('=')
                @Suppress("DEPRECATION")
                describe(value.get(key), out)
                out.append(';')
            }
            is Array<*> -> value.forEach { describe(it, out) }
            is Iterable<*> -> value.forEach { describe(it, out) }
            is CharArray -> out.append(String(value))
            is ByteArray -> out.append(String(value, Charsets.UTF_8))
            else -> out.append(value.toString())
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val QUERY = "syntheticqueryzq"
    }
}
