package org.openlife.vault.repository

import android.graphics.BitmapFactory

/**
 * Production [BitmapSampler]. Uses `inJustDecodeBounds` first (no pixel
 * allocation) purely to confirm the platform decoder accepts the bytes,
 * then a bounded sampled decode capped at [ImportLimits.MAX_PREVIEW_PIXELS]
 * — never a full-size allocation just to validate (design §12).
 */
class AndroidBitmapSampler : BitmapSampler {
    override fun canSample(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sampleSize = 1
        while ((bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize) >
            ImportLimits.MAX_PREVIEW_PIXELS
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        val decoded = sampled != null
        sampled?.recycle()
        return decoded
    }
}
