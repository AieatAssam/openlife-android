package org.openlife.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.OrientationTransform
import org.openlife.vault.repository.ImportLimits

/**
 * Decodes authenticated preview bytes without ever asking the platform to
 * allocate the full-size image. The bounds pass is allocation-free; every
 * sampled decode is checked after decoding as some platform codecs round
 * dimensions differently from the requested [BitmapFactory.Options.inSampleSize].
 */
object SampledBitmapDecoder {

    fun decode(bytes: ByteArray): Bitmap? = decode(bytes, Orientation.NORMAL)

    fun decode(bytes: ByteArray, orientation: Orientation): Bitmap? =
        decode(bytes, orientation, ImportLimits.MAX_PREVIEW_PIXELS)

    fun decode(
        bytes: ByteArray,
        orientation: Orientation,
        maxPixels: Long,
        preferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        require(maxPixels > 0) { "Maximum decode pixels must be positive" }
        val sampled = decodeSampled(bytes, maxPixels, preferredConfig) ?: return null
        val matrix = OrientationTransform.matrixFor(orientation) ?: return sampled
        val transformed = Bitmap.createBitmap(
            sampled,
            0,
            0,
            sampled.width,
            sampled.height,
            matrix,
            true,
        )
        if (transformed !== sampled) sampled.recycle()
        return transformed
    }

    private fun decodeSampled(bytes: ByteArray, maxPixels: Long, preferredConfig: Bitmap.Config): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = initialSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        var decoded: Bitmap? = null
        var exhausted = false
        while (decoded == null && !exhausted) {
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = preferredConfig
                },
            )
            if (bitmap == null) {
                exhausted = true
            } else {
                val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
                if (pixelCount <= maxPixels) {
                    decoded = bitmap
                } else {
                    // A codec may round up despite inSampleSize. Recycle that
                    // result before retrying so a hostile image cannot
                    // accumulate bitmaps.
                    bitmap.recycle()
                    if (sampleSize > Int.MAX_VALUE / 2) {
                        exhausted = true
                    } else {
                        sampleSize *= 2
                    }
                }
            }
        }
        return decoded
    }

    private fun initialSampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sampleSize = 1
        while ((width / sampleSize).toLong() * (height / sampleSize).toLong() >
            maxPixels
        ) {
            if (sampleSize > Int.MAX_VALUE / 2) return sampleSize
            sampleSize *= 2
        }
        return sampleSize
    }
}
