package org.openlife.app.ui

import android.app.ActivityManager
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.ImportLimits
import org.openlife.vault.repository.PrepareResult
import org.openlife.vault.repository.ReadyReadResult
import org.openlife.vault.repository.SaveResult

/**
 * P1-16-R4: an import close to the 16 MiB limit prepares, saves, and opens
 * through the viewer's sampled decode. CI runs this on 2 GB emulators (the
 * supported RAM floor); the device's memory class is logged with the result.
 */
@RunWith(AndroidJUnit4::class)
class LargeImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as OpenLifeApp

    @Test
    fun nearLimitImportSavesAndOpensAtTheRamFloor(): Unit = runBlocking {
        val bytes = nearLimitJpeg()
        val access = app.vault() as VaultAccess.Ready
        val prepared = access.importRepository.prepareImport(ByteArrayInputStream(bytes), "image/jpeg", IntakeKind.SHARE)
        assertTrue("prepare: $prepared", prepared is PrepareResult.Prepared)
        val id = (prepared as PrepareResult.Prepared).sourceId
        try {
            assertTrue(access.importRepository.saveImport(id) is SaveResult.Saved)
            val read = access.viewRepository.readReadyBytes(id)
            assertTrue("read: $read", read is ReadyReadResult.Loaded)
            val plaintext = (read as ReadyReadResult.Loaded).bytes
            val bitmap = SampledBitmapDecoder.decode(plaintext)
            plaintext.fill(0)
            assertNotNull("viewer decode failed", bitmap)
            bitmap!!.recycle()
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            Log.i(TAG, "bytes=${bytes.size} totalMem=${memory.totalMem} memoryClass=${activityManager.memoryClass}")
        } finally {
            access.deletionRepository.deleteSource(id)
        }
    }

    /** Noise JPEG at the highest quality that still fits under the limit (a worst case for size). */
    private fun nearLimitJpeg(): ByteArray {
        val random = Random(SEED)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val row = IntArray(WIDTH)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) row[x] = random.nextInt() or OPAQUE
            bitmap.setPixels(row, 0, WIDTH, 0, y, WIDTH, 1)
        }
        try {
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= ImportLimits.MAX_ORIGINAL_BYTES) {
                    assertTrue("fixture too small: ${out.size()}", out.size() >= MIN_FIXTURE_BYTES)
                    return out.toByteArray()
                }
            }
        } finally {
            bitmap.recycle()
        }
        throw AssertionError("no quality fits under the limit")
    }

    private companion object {
        const val TAG = "P116LargeImport"
        const val WIDTH = 4000
        const val HEIGHT = 3000
        const val SEED = 16L
        const val OPAQUE = 0xFF000000.toInt()
        const val MIN_FIXTURE_BYTES = 12L * 1024 * 1024
        val QUALITIES = (95 downTo 70).toList()
    }
}
