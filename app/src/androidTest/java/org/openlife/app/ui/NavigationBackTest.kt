package org.openlife.app.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.createReadySource
import org.openlife.app.test.deleteSyntheticSource
import org.openlife.app.test.requestOpenSource

/**
 * P0-07-R3 and P1-20-R6 (replaces the source-grep unit NavigationBackTest):
 * Back from every destination, exercised on the real activity. Back pops one
 * entry; from the list root it finishes the activity.
 */
@RunWith(AndroidJUnit4::class)
class NavigationBackTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var sourceId: UUID? = null

    @Before
    fun acknowledgeFirstRun() {
        FirstRunPreferences.setAcknowledged(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun cleanUp() {
        sourceId?.let(application::deleteSyntheticSource)
    }

    @Test
    fun backFromViewerReturnsToListInsteadOfFinishing() {
        sourceId = application.createReadySource().also(application::requestOpenSource)
        ActivityScenario.launch<MainActivity>(Intent(application, MainActivity::class.java)).use { scenario ->
            assertTrue("viewer did not load the image", device.wait(Until.hasObject(By.desc(SAVED_IMAGE)), WAIT_MS))

            pressBack()

            assertTrue("the viewer stayed after Back", device.wait(Until.gone(By.desc(SAVED_IMAGE)), WAIT_MS))
            assertTrue("list not shown after Back", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))
            var finishing = true
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("back from viewer must not finish MainActivity", finishing)
        }
    }

    /**
     * A-10 (P1-19-R14): the delete callback and the source flow both ask the
     * viewer to leave; it leaves once (ViewerSession.consumeExit, proven on
     * the JVM for both orders), so the list stays and the activity stays open.
     * P1-20 removed the beforeDeleteResultForTest seam that forced the order.
     */
    @Test
    fun deleteFromViewerKeepsListOnTheBackStack() {
        sourceId = application.createReadySource().also(application::requestOpenSource)
        ActivityScenario.launch<MainActivity>(Intent(application, MainActivity::class.java)).use { scenario ->
            assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc(SAVED_IMAGE)), WAIT_MS))
            device.findObject(By.descStartsWith("Delete ")).click()
            device.wait(Until.findObject(By.res("delete_confirm")), WAIT_MS)?.click()
                ?: device.wait(Until.findObject(By.text("Delete")), WAIT_MS)!!.click()

            assertTrue("the viewer did not leave", device.wait(Until.gone(By.desc(SAVED_IMAGE)), WAIT_MS))
            assertTrue("the list is not shown", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))
            device.waitForIdle()
            assertTrue("the list was popped by a second exit", device.hasObject(By.text(IMPORT)))
            var finishing = true
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("MainActivity must stay open", finishing)
        }
        sourceId = null
    }

    /** P1-20-R6: Search, Settings, About and the viewer each return to the list on Back. */
    @Test
    fun backFromEveryDestination() {
        sourceId = application.createReadySource()
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("list not shown", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))

            device.findObject(By.desc("Search your items")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Words in your items")), WAIT_MS))
            backToList("Search")

            listOf("Settings", "About").forEach { title ->
                device.findObject(By.desc("More options")).click()
                device.wait(Until.findObject(By.text(title)), WAIT_MS)!!.click()
                assertTrue("$title not shown", device.wait(Until.gone(By.text(IMPORT)), WAIT_MS))
                backToList(title)
            }

            device.findObject(By.desc("Saved image thumbnail")).click()
            assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc(SAVED_IMAGE)), WAIT_MS))
            pressBack()
            assertTrue("viewer stayed after Back", device.wait(Until.gone(By.desc(SAVED_IMAGE)), WAIT_MS))
        }
    }

    @Test
    fun backFromListFinishesActivity() {
        val destroyed = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.DESTROYED) destroyed.countDown()
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("list not shown", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))
                // pressBack() throws NoActivityResumedException when the activity
                // finishes, which is the P0-07-R3 list-root behaviour.
                pressBackUnconditionally()
                assertTrue("Back from the list must finish MainActivity", destroyed.await(WAIT_MS, TimeUnit.MILLISECONDS))
            }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
    }

    private fun backToList(from: String) {
        pressBack()
        assertTrue("Back from $from did not return to the list", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val SAVED_IMAGE = "Saved image"
        const val IMPORT = "Import from photos"
    }
}
