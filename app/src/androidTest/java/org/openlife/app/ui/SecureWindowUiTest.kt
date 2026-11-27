package org.openlife.app.ui

import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.intake.IntakeActivity
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.createReadySource
import org.openlife.app.test.deleteSyntheticSource

/**
 * P1-20-R6 (replaces the source-grep SecureWindowTest unit test once it has
 * run on a device): every window this process shows — the activities, the
 * overflow menu popup and the delete dialog — carries FLAG_SECURE.
 * WindowInspector lists the process's own root views. It is API 29+, so on
 * API 26-28 this test is skipped and FLAG_SECURE is not checked (a device gap).
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowUiTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private val device = UiDevice.getInstance(instrumentation)
    private var sourceId: UUID? = null

    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(application)
    }

    @After
    fun cleanUp() {
        sourceId?.let(application::deleteSyntheticSource)
    }

    @Test
    @SdkSuppress(minSdkVersion = android.os.Build.VERSION_CODES.Q)
    fun everyContentWindowAndDialogIsSecure() {
        org.junit.Assume.assumeTrue(
            "WindowInspector needs API 29; this device is API ${android.os.Build.VERSION.SDK_INT}",
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
        )
        sourceId = application.createReadySource()
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("Import from photos")), WAIT_MS))
            assertAllWindowsSecure("list", expectedAtLeast = 1)

            device.findObject(By.desc("More options")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Settings")), WAIT_MS))
            assertAllWindowsSecure("overflow menu", expectedAtLeast = 2)
            device.pressBack()

            device.wait(Until.findObject(By.descStartsWith("Delete ")), WAIT_MS)!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("Cancel")), WAIT_MS))
            assertAllWindowsSecure("delete dialog", expectedAtLeast = 2)
            device.findObject(By.text("Cancel")).click()
        }
        ActivityScenario.launch(IntakeActivity::class.java).use {
            instrumentation.waitForIdleSync()
            assertAllWindowsSecure("intake", expectedAtLeast = 1)
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun assertAllWindowsSecure(where: String, expectedAtLeast: Int) {
        instrumentation.waitForIdleSync()
        var views: List<View> = emptyList()
        instrumentation.runOnMainSync { views = WindowInspector.getGlobalWindowViews() }
        val shown = views.filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE }
        assertTrue("$where: expected at least $expectedAtLeast windows, saw ${shown.size}", shown.size >= expectedAtLeast)
        shown.forEach { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            assertTrue(
                "$where: window ${params.title} lacks FLAG_SECURE",
                params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
            )
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
    }
}
