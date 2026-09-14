package org.openlife.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.openlife.vault.repository.ImportLimits

/**
 * Decodes authenticated preview bytes without ever asking the platform to
 * allocate the full-size image. The bounds pass is allocation-free; every
 * sampled decode is checked after decoding as some platform codecs round
 * dimensions differently from the requested [BitmapFactory.Options.inSampleSize].
 */
object SampledBitmapDecoder {

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = initialSampleSize(bounds.outWidth, bounds.outHeight)
        while (true) {
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null

            val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
            if (pixelCount <= ImportLimits.MAX_PREVIEW_PIXELS) return bitmap

            // A codec may round up despite inSampleSize. Recycle that result
            // before retrying so a hostile image cannot accumulate bitmaps.
            bitmap.recycle()
            if (sampleSize > Int.MAX_VALUE / 2) return null
            sampleSize *= 2
        }
    }

    private fun initialSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while ((width / sampleSize).toLong() * (height / sampleSize).toLong() >
            ImportLimits.MAX_PREVIEW_PIXELS
        ) {
            if (sampleSize > Int.MAX_VALUE / 2) return sampleSize
            sampleSize *= 2
        }
        return sampleSize
    }
}
