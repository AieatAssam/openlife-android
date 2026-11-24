package org.openlife.app.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.app.test.StrictModeRule
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult

/**
 * P1-16-R2: in split-screen an activity can be STARTED without being
 * RESUMED while still visible. Content is scrubbed when the activity is
 * hidden (CREATED, onStop), not when it merely loses focus (onPause).
 */
@RunWith(AndroidJUnit4::class)
class MultiWindowScrubTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as OpenLifeApp
    private val device = UiDevice.getInstance(instrumentation)
    private var savedId: UUID? = null

    @Before
    fun setUp() {
        FirstRunPreferences.setAcknowledged(context)
    }

    @After
    fun tearDown() {
        runBlocking {
            val access = app.vault() as? VaultAccess.Ready ?: return@runBlocking
            savedId?.let { access.deletionRepository.deleteSource(it) }
        }
    }

    @Test
    fun startedNotResumedKeepsContentAndCreatedScrubs() {
        val id = saveSyntheticSource()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SOURCE_ID, id.toString())
            .putExtra(MainActivity.EXTRA_OPEN_SOURCE_NONCE, app.openSourceNonce.issue())

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS))
            val scrubsBefore = scrubCount(scenario)

            // Focus lost, still visible (split-screen partner focused).
            scenario.moveToState(Lifecycle.State.STARTED)
            assertEquals("losing focus must not scrub", scrubsBefore, scrubCount(scenario))
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(
                "the viewer must still be open after a focus change",
                device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS),
            )

            // Hidden.
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("hiding must scrub", scrubsBefore + 1, scrubCount(scenario))
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(
                "after being hidden the viewer returns to the list",
                device.wait(Until.hasObject(By.desc("More options")), WAIT_MS),
            )
            assertTrue(device.wait(Until.gone(By.desc("Saved image")), WAIT_MS))
        }
    }

    /**
     * A-06 (P1-19-R13, suspected): the viewer's decoded bitmap is freed by a
     * DisposableEffect, which runs only when Compose recomposes. Checked
     * while the activity is still stopped (before ON_START): is any decoded
     * bitmap still alive and not recycled?
     */
    @Test
    fun decodedBitmapsAreReleasedWhileHidden() {
        val id = saveSyntheticSource()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SOURCE_ID, id.toString())
            .putExtra(MainActivity.EXTRA_OPEN_SOURCE_NONCE, app.openSourceNonce.issue())

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS))
            awaitTrue("the viewer bitmap was never decoded") { DecodedBitmapProbe.liveCountForTest() > 0 }

            scenario.moveToState(Lifecycle.State.CREATED)
            val released = pollWhileStopped { DecodedBitmapProbe.liveCountForTest() == 0 }
            val live = DecodedBitmapProbe.liveCountForTest()
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue("decoded bitmaps alive while hidden: $live", released)
        }
    }

    /** Polls with GC for up to [STOPPED_WINDOW_MS]; never moves the activity. */
    private fun pollWhileStopped(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + STOPPED_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            Runtime.getRuntime().gc()
            instrumentation.waitForIdleSync()
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            Thread.sleep(POLL_MS)
        }
    }

    private fun scrubCount(scenario: ActivityScenario<MainActivity>): Long {
        var count = 0L
        scenario.onActivity { count = it.scrubCountForTest() }
        return count
    }

    private fun saveSyntheticSource(): UUID = runBlocking {
        val access = app.vault() as VaultAccess.Ready
        val random = Random(System.nanoTime())
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(WIDTH * HEIGHT) { random.nextInt() or OPAQUE }, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        val prepared = access.importRepository.prepareImport(
            ByteArrayInputStream(out.toByteArray()),
            "image/jpeg",
            IntakeKind.SHARE,
        ) as PrepareResult.Prepared
        assertTrue(access.importRepository.saveImport(prepared.sourceId) is SaveResult.Saved)
        prepared.sourceId.also { savedId = it }
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val STOPPED_WINDOW_MS = 3_000L
        const val POLL_MS = 100L
        const val WIDTH = 64
        const val HEIGHT = 48
        const val JPEG_QUALITY = 90
        const val OPAQUE = 0xFF000000.toInt()
    }
}
