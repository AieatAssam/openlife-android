package org.openlife.app.intake

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.test.LEGACY_OPEN_SOURCE_EXTRA
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.createReadySource
import org.openlife.app.test.deleteSyntheticSource
import org.openlife.app.ui.FirstRunPreferences

/**
 * P1-20-R5: "open existing" travels through the single non-exported
 * InternalLinkActivity with a single-use nonce; MainActivity, the exported
 * launcher, ignores the old open-source extra.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class InternalLinkTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
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
    fun openExistingGoesThroughTheInternalEntryWithNonce() {
        val id = application.createReadySource().also { sourceId = it }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var main: Activity? = null
        scenario.onActivity { main = it }
        try {
            assertTrue("list not shown", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))
            val link = InternalLinkActivity.openSourceIntent(application, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            application.startActivity(Intent(link))
            assertTrue("the internal link did not open the viewer", device.wait(Until.hasObject(By.desc(IMAGE)), WAIT_MS))
            pressBack()
            assertTrue("list not shown after Back", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))

            // The same nonce again: refused, so the viewer does not open.
            application.startActivity(Intent(link))
            assertFalse("a replayed nonce opened the viewer", device.wait(Until.hasObject(By.desc(IMAGE)), REFUSAL_MS))
        } finally {
            // The forwarded link reaches MainActivity by onNewIntent, which setIntent()s it. ActivityScenario
            // matches lifecycle events to the launch intent, so close() would wait for a DESTROYED it ignores
            // and time out. Finish the activity directly and wait for its own state instead.
            scenario.onActivity { it.finish() }
            awaitDestroyed(requireNotNull(main) { "MainActivity was never observed" })
        }
    }

    private fun awaitDestroyed(activity: Activity) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!activity.isDestroyed) {
            check(SystemClock.uptimeMillis() < deadline) { "MainActivity was not destroyed after finish()" }
            SystemClock.sleep(POLL_MS)
        }
    }

    @Test
    fun launcherIgnoresOpenSourceExtra() {
        val id = application.createReadySource().also { sourceId = it }
        val intent = Intent(application, MainActivity::class.java).putExtra(LEGACY_OPEN_SOURCE_EXTRA, id.toString())
        ActivityScenario.launch<MainActivity>(intent).use {
            assertTrue("list not shown", device.wait(Until.hasObject(By.text(IMPORT)), WAIT_MS))
            assertFalse("the launcher opened a source from an extra", device.wait(Until.hasObject(By.desc(IMAGE)), REFUSAL_MS))
        }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val REFUSAL_MS = 5_000L
        const val POLL_MS = 50L
        const val IMPORT = "Import from photos"
        const val IMAGE = "Saved image"
    }
}
