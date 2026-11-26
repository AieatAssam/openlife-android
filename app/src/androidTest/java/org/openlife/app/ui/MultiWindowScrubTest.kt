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
import org.openlife.app.test.requestOpenSource
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.SaveResult

/**
 * P1-16-R2: in split-screen an activity can be STARTED without being
 * RESUMED while still visible. Content is scrubbed when the activity is
 * hidden (CREATED, onStop), not when it merely loses focus (onPause).
 * P1-20-R4: scrubbing drops content only; the destination stays.
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
        app.requestOpenSource(id)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
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

            // Hidden: scrubbed, but (P1-20-R4) the destination is kept and reloads.
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals("hiding must scrub", scrubsBefore + 1, scrubCount(scenario))
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(
                "after being hidden the viewer is still the destination and reloads its image",
                device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS),
            )
        }
    }

    /**
     * P1-20-R4 (AS-4): while stopped no decoded bitmap is held (the viewer
     * session recycles it synchronously in onStop, not on recomposition); on
     * return the same destination reloads the same source by id. OCR text
     * leaving memory while hidden is proven on the JVM
     * (ViewerSessionTest.hidingClearsContentAndOcrTextSynchronouslyButKeepsTheDestination).
     */
    @Test
    fun backgroundingClearsContentButKeepsTheDestination() {
        val id = saveSyntheticSource()
        app.requestOpenSource(id)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS))
            assertTrue("the viewer bitmap was decoded", DecodedBitmapProbe.liveCountForTest() > 0)

            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.waitForIdleSync()
            assertEquals("decoded bitmaps alive while hidden", 0, DecodedBitmapProbe.liveCountForTest())

            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(
                "the same destination reloads its content",
                device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS),
            )
            assertTrue("reloaded by id", DecodedBitmapProbe.liveCountForTest() > 0)
        }
    }

    /**
     * A-06 (P1-19-R13): checked while the activity is still stopped (before
     * ON_START), with no wait: the release is synchronous since P1-20-R4.
     */
    @Test
    fun decodedBitmapsAreReleasedWhileHidden() {
        val id = saveSyntheticSource()
        app.requestOpenSource(id)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            assertTrue("viewer not shown", device.wait(Until.hasObject(By.desc("Saved image")), WAIT_MS))

            scenario.moveToState(Lifecycle.State.CREATED)
            val live = DecodedBitmapProbe.liveCountForTest()
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals("decoded bitmaps alive while hidden", 0, live)
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
        const val WIDTH = 64
        const val HEIGHT = 48
        const val JPEG_QUALITY = 90
        const val OPAQUE = 0xFF000000.toInt()
    }
}
