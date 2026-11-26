package org.openlife.app.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import org.openlife.app.OpenLifeApp
import org.openlife.app.test.StrictModeRule
import org.openlife.app.test.createReadySource
import org.openlife.app.test.deleteSyntheticSource
import org.openlife.app.test.requestOpenSource
import java.util.UUID

/**
 * Review N-05: the by-id viewer path of [ViewerByIdTest] through the real
 * container — Room's observeVisibleSource, the lock gate and the vault read —
 * on the real MainActivity, opened through the internal-link queue. The
 * lock policy is the default (off), as in InternalLinkTest.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class ViewerByIdRealStoreTest {
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
    fun viewerOpensASourceByIdThroughTheRealStore() {
        val id = application.createReadySource().also { sourceId = it }
        application.requestOpenSource(id)

        ActivityScenario.launch(MainActivity::class.java).use {
            val shown = device.wait(Until.hasObject(By.desc(IMAGE)), WAIT_MS)
            assertTrue("the real store did not show the source", shown)
        }
    }

    private companion object {
        const val IMAGE = "Saved image"
        const val WAIT_MS = 30_000L
    }
}
