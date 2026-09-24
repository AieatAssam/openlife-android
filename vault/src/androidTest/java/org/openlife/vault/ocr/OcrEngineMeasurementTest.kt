package org.openlife.vault.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation

/**
 * P2-03-R7 measurement, not a gate: duration and peak PSS per image for each
 * engine on synthetic fixtures. P2-04 defines the evaluation fixtures; until
 * then these stand in. Opt-in with
 * `-Pandroid.testInstrumentationRunnerArguments.p203Measure=true`; results go
 * to logcat under `P203Measure`.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineMeasurementTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dataDir = File(context.noBackupFilesDir, "measure-tessdata-${UUID.randomUUID()}")

    @After
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    @Test
    fun measureEnginesOnSyntheticFixtures(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("p203Measure") == "true")
        val engines = listOf(
            TesseractOcrEngine(TessdataInstaller(dataDir, { context.assets.open(TessdataInstaller.ASSET_PATH) })),
            MlKitOcrEngine(),
        )
        val fixtures = mapOf(
            "receipt-900x360" to page(900, 360, lines = 2, textSize = 64f),
            "screenshot-1080x2400" to page(1080, 2400, lines = 20, textSize = 44f),
            "dense-1200x1600" to page(1200, 1600, lines = 40, textSize = 30f),
        )
        for (engine in engines) {
            engine.extract(fixtures.getValue("receipt-900x360")) // warm-up: model load
            for ((name, input) in fixtures) {
                val durations = mutableListOf<Long>()
                var peakKb = 0L
                repeat(RUNS) {
                    val peak = AtomicLong(Debug.getPss())
                    val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
                    val sampler = thread {
                        while (sampling.get()) {
                            peak.accumulateAndGet(Debug.getPss(), ::maxOf)
                            Thread.sleep(SAMPLE_MS)
                        }
                    }
                    val start = System.nanoTime()
                    val spans = engine.extract(input).spans.size
                    durations += (System.nanoTime() - start) / NANOS_PER_MS
                    sampling.set(false)
                    sampler.join()
                    peakKb = maxOf(peakKb, peak.get())
                    Log.i(TAG, "engine=${engine.id} fixture=$name spans=$spans")
                }
                Log.i(TAG, "engine=${engine.id} fixture=$name ms=$durations peakPssKb=$peakKb")
            }
        }
    }

    private fun page(width: Int, height: Int, lines: Int, textSize: Float): OcrEngineInput {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
        }
        val step = height / (lines + 1f)
        repeat(lines) { line -> canvas.drawText("Item $line quick brown fox 12.50", 30f, step * (line + 1), paint) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return OcrEngineInput(out.toByteArray(), width, height, Orientation.NORMAL, ImageFormat.JPEG)
    }

    private companion object {
        const val TAG = "P203Measure"
        const val RUNS = 3
        const val SAMPLE_MS = 50L
        const val JPEG_QUALITY = 92
        const val NANOS_PER_MS = 1_000_000L
    }
}
