package org.openlife.vault.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import kotlin.math.abs

/** P2-03-R2: the open-source engine extracts Latin lines with regions in source pixels and stops on cancel. */
@RunWith(AndroidJUnit4::class)
class TesseractOcrEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.noBackupFilesDir, "test-tessdata-${UUID.randomUUID()}")
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val engine = TesseractOcrEngine(
        TessdataInstaller(dataDir, openAsset = { context.assets.open(TessdataInstaller.ASSET_PATH) }),
        events = { events += it },
    )

    @After
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    @Test
    fun extractsSyntheticLatinTextWithLineRegionsInSourcePixels(): Unit = runBlocking {
        val upright = twoLineReceipt()
        val output = engine.extract(input(upright, Orientation.NORMAL))

        val invoice = output.spans.single { it.text.uppercase().contains("INVOICE") }
        val total = output.spans.single { it.text.uppercase().contains("TOTAL") }
        assertTrue("regions are grounded", invoice.region != null && total.region != null)
        assertTrue("reading order", invoice.region!!.top < total.region!!.top)
        assertNear("first line sits where it was drawn", FIRST_LINE_CENTRE, centreY(invoice.region!!))
        assertNear("second line sits where it was drawn", SECOND_LINE_CENTRE, centreY(total.region!!))
        output.spans.forEach { span ->
            val region = span.region!!
            assertTrue(region.left >= 0 && region.top >= 0 && region.right <= WIDTH && region.bottom <= HEIGHT)
            assertEquals("R6: no uncalibrated confidence", null, span.confidence)
        }
    }

    @Test
    fun rotatedInputRegionsMapBack(): Unit = runBlocking {
        val upright = twoLineReceipt()
        val normal = engine.extract(input(upright, Orientation.NORMAL))
        // Stored as if the camera was turned: displaying it needs a 90° clockwise turn (P1-03).
        val stored = Bitmap.createBitmap(upright, 0, 0, WIDTH, HEIGHT, Matrix().apply { postRotate(-90f) }, true)
        val rotated = engine.extract(input(stored, Orientation.ROTATE_90))

        val expected = normal.spans.single { it.text.uppercase().contains("TOTAL") }.region!!
        val actual = rotated.spans.single { it.text.uppercase().contains("TOTAL") }.region!!
        val mapped = OcrCoordinateMapper.toSourcePixels(expected, stored.width, stored.height, Orientation.ROTATE_90)
        listOf(
            mapped.left to actual.left,
            mapped.top to actual.top,
            mapped.right to actual.right,
            mapped.bottom to actual.bottom,
        ).forEach { (want, got) -> assertTrue("$mapped vs $actual", abs(want - got) <= REGION_TOLERANCE) }
    }

    @Test
    fun cancellationStopsRecognitionAndRecyclesAfterCompletion(): Unit = runBlocking {
        val page = densePage()
        val job = launch(Dispatchers.Default) { engine.extract(input(page, Orientation.NORMAL)) }
        // stop() acts in the recognition phase; layout analysis before it is not interruptible.
        withTimeout(START_TIMEOUT_MS) { while ("recognizing" !in events) delay(POLL_MS) }

        val started = System.nanoTime()
        job.cancelAndJoin()
        val stoppedWithinMs = (System.nanoTime() - started) / NANOS_PER_MS

        assertTrue(job.isCancelled)
        assertTrue("stop() ends recognition promptly (took ${stoppedWithinMs}ms)", stoppedWithinMs < STOP_BUDGET_MS)
        val stop = events.indexOf("stop")
        val settled = events.indexOf("settled")
        val recycled = events.indexOf("recycled")
        assertTrue("events: $events", stop in 0 until settled && settled < recycled)
    }

    /** Cancelled before recognition began: the caller is released and the native state freed once it returns. */
    @Test
    fun earlyCancellationFreesNativeStateOnceRecognitionReturns(): Unit = runBlocking {
        val job = launch(Dispatchers.Default) { engine.extract(input(twoLineReceipt(), Orientation.NORMAL)) }
        withTimeout(START_TIMEOUT_MS) { while ("recognition-started" !in events) delay(POLL_MS) }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        withTimeout(START_TIMEOUT_MS) { while ("recycled" !in events) delay(POLL_MS) }
        assertTrue("freed only after the native call settled: $events", events.indexOf("settled") < events.indexOf("recycled"))
    }

    private fun input(bitmap: Bitmap, orientation: Orientation): OcrEngineInput {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return OcrEngineInput(out.toByteArray(), bitmap.width, bitmap.height, orientation, ImageFormat.JPEG)
    }

    private fun twoLineReceipt(): Bitmap = page(WIDTH, HEIGHT) { canvas, paint ->
        canvas.drawText("INVOICE 2026", MARGIN, FIRST_BASELINE, paint)
        canvas.drawText("TOTAL 42.50", MARGIN, SECOND_BASELINE, paint)
    }

    private fun densePage(): Bitmap = page(DENSE_WIDTH, DENSE_HEIGHT) { canvas, paint ->
        paint.textSize = DENSE_TEXT_SIZE
        repeat(DENSE_LINES) { line ->
            canvas.drawText(
                "Line $line: the quick brown fox jumps over the lazy dog 0123456789",
                MARGIN,
                DENSE_LINE_HEIGHT * (line + 1),
                paint,
            )
        }
    }

    private fun page(width: Int, height: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        draw(canvas, paint)
        return bitmap
    }

    private fun centreY(region: OcrEvidenceRegion) = (region.top + region.bottom) / 2f

    private fun assertNear(message: String, expected: Float, actual: Float) =
        assertTrue("$message: expected ~$expected, was $actual", abs(expected - actual) <= LINE_TOLERANCE)

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 360
        const val MARGIN = 40f
        const val TEXT_SIZE = 64f
        const val FIRST_BASELINE = 130f
        const val SECOND_BASELINE = 270f
        const val FIRST_LINE_CENTRE = 107f
        const val SECOND_LINE_CENTRE = 247f
        const val LINE_TOLERANCE = 35f
        const val REGION_TOLERANCE = 12
        const val JPEG_QUALITY = 95
        const val DENSE_WIDTH = 2400
        const val DENSE_HEIGHT = 3200
        const val DENSE_TEXT_SIZE = 42f
        const val DENSE_LINE_HEIGHT = 62f
        const val DENSE_LINES = 50
        const val START_TIMEOUT_MS = 30_000L
        const val STOP_BUDGET_MS = 5_000L
        const val POLL_MS = 10L
        const val NANOS_PER_MS = 1_000_000L
    }
}
