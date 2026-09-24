package org.openlife.vault.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.OrientationTransform

/**
 * Shared by every engine: decodes the authenticated bytes within the OCR
 * pixel limits (sampling down if needed) and turns the bitmap upright, so
 * engines see the image as the viewer shows it. Regions are mapped back to
 * source pixels by [OcrCoordinateMapper].
 */
object OcrBitmapPreparer {
    fun prepare(input: OcrEngineInput): Bitmap {
        val decoded = decodeBounded(input)
        val upright = orient(decoded, input.orientation)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    private fun decodeBounded(input: OcrEngineInput): Bitmap {
        val bounds = checkedBounds(input)
        var sampleSize = 1
        while ((bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize).toLong() >
            OcrLimits.MAX_DECODE_PIXELS
        ) {
            sampleSize = sampleSize shl 1
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size, options)
            ?: throw OcrDecodeException("OCR source could not be decoded")
    }

    private fun checkedBounds(input: OcrEngineInput): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw OcrDecodeException("OCR source could not be decoded")
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > OcrLimits.MAX_SOURCE_PIXELS) {
            throw OcrLimitExceededException("OCR source exceeds the pixel limit")
        }
        return bounds
    }

    private fun orient(bitmap: Bitmap, orientation: Orientation): Bitmap {
        val matrix = OrientationTransform.matrixFor(orientation) ?: return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
