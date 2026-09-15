package org.openlife.vault.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.openlife.vault.model.Orientation

/**
 * Bundled, Latin-only ML Kit adapter. It owns no persistence and has no URI or
 * provider access; the caller supplies an already authenticated bounded byte
 * snapshot. The recognizer is closed after every operation so a timed-out or
 * cancelled task cannot retain a decoded bitmap.
 */
class MlKitOcrEngine : OcrEngine {
    override val id: String = "mlkit-latin"
    override val modelVersion: String = "16.0.1"

    override suspend fun extract(input: OcrEngineInput): OcrEngineOutput = withTimeout(OcrLimits.DEADLINE_MILLIS) {
        val decoded = decodeBounded(input)
        val display = orient(decoded, input.orientation)
        if (display !== decoded) decoded.recycle()

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = awaitTask(recognizer.process(InputImage.fromBitmap(display, 0)))
            val displaySourceWidth = if (swapsAxes(input.orientation)) input.height else input.width
            val displaySourceHeight = if (swapsAxes(input.orientation)) input.width else input.height
            val scaleX = displaySourceWidth.toDouble() / display.width.toDouble()
            val scaleY = displaySourceHeight.toDouble() / display.height.toDouble()
            val spans = text.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    OcrSpanDraft(
                        text = line.text,
                        confidence = line.confidence,
                        region = line.boundingBox?.let { scaleRegion(it, display.width, display.height, scaleX, scaleY) }
                            ?.let {
                                OcrCoordinateMapper.toSourcePixels(
                                    it,
                                    input.width,
                                    input.height,
                                    input.orientation,
                                )
                            },
                    )
                }
            }
            OcrOutputValidator.validate(spans)
            if (OcrScriptPolicy.classify(spans) == OcrScriptStatus.UNSUPPORTED) {
                throw OcrUnsupportedScriptException()
            }
            OcrEngineOutput(spans)
        } finally {
            recognizer.close()
            display.recycle()
        }
    }

    private fun decodeBounded(input: OcrEngineInput): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "OCR source could not be decoded" }
        require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= OcrLimits.MAX_SOURCE_PIXELS) {
            "OCR source exceeds the pixel limit"
        }

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
            ?: throw IllegalArgumentException("OCR source could not be decoded")
    }

    private fun orient(bitmap: Bitmap, orientation: Orientation): Bitmap {
        if (orientation == Orientation.NORMAL) return bitmap
        val matrix = Matrix()
        when (orientation) {
            Orientation.ROTATE_90 -> matrix.setRotate(90f)
            Orientation.ROTATE_180 -> matrix.setRotate(180f)
            Orientation.ROTATE_270 -> matrix.setRotate(270f)
            Orientation.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            Orientation.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            Orientation.TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            Orientation.TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            Orientation.NORMAL -> Unit
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleRegion(
        rect: Rect,
        displayWidth: Int,
        displayHeight: Int,
        scaleX: Double,
        scaleY: Double,
    ): OcrEvidenceRegion {
        val left = (rect.left * scaleX).toInt().coerceIn(0, displayWidth)
        val top = (rect.top * scaleY).toInt().coerceIn(0, displayHeight)
        val right = kotlin.math.ceil(rect.right * scaleX).toInt().coerceIn(left, displayWidth)
        val bottom = kotlin.math.ceil(rect.bottom * scaleY).toInt().coerceIn(top, displayHeight)
        return OcrEvidenceRegion(left, top, right, bottom)
    }

    private fun swapsAxes(orientation: Orientation): Boolean = when (orientation) {
        Orientation.ROTATE_90,
        Orientation.ROTATE_270,
        Orientation.TRANSPOSE,
        Orientation.TRANSVERSE,
        -> true
        else -> false
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { continuation ->
        task.addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        task.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

class OcrUnsupportedScriptException : IllegalArgumentException("OCR output uses an unsupported script")
