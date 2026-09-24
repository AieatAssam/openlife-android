package org.openlife.vault.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import org.openlife.vault.model.OrientationTransform

/**
 * Bundled, Latin-only ML Kit adapter. It owns no persistence and has no URI or
 * provider access; the caller supplies an already authenticated bounded byte
 * snapshot. The deadline is the repository's alone (P2-02-R2). Cancellation
 * closes the recognizer, which abandons its task, and the decoded bitmap is
 * recycled only once that task has settled: if it never does, the bitmap is
 * left to the garbage collector rather than recycled under a running
 * recognizer (P2-02-R4).
 */
class MlKitOcrEngine : OcrEngine {
    override val id: String = "mlkit-latin"
    override val modelVersion: String = "16.0.1"

    override suspend fun extract(input: OcrEngineInput): OcrEngineOutput {
        val display = OcrBitmapPreparer.prepare(input)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val task = RecognizerTask(recognizer.process(InputImage.fromBitmap(display, 0)), recognizer)
        try {
            val text = awaitEngineTask(task)
            val displaySourceWidth = OrientationTransform.displayWidth(input.width, input.height, input.orientation)
            val displaySourceHeight = OrientationTransform.displayHeight(input.width, input.height, input.orientation)
            val scaleX = displaySourceWidth.toDouble() / display.width.toDouble()
            val scaleY = displaySourceHeight.toDouble() / display.height.toDouble()
            val spans = text.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    OcrSpanDraft(
                        text = line.text,
                        // R6: ML Kit's score is not stored until P2-04 calibrates it.
                        confidence = OcrConfidencePolicy.stored(line.confidence),
                        region = line.boundingBox?.let {
                            scaleRegion(
                                it,
                                display.width,
                                display.height,
                                scaleX,
                                scaleY,
                            )
                        }
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
            return OcrEngineOutput(spans)
        } finally {
            recognizer.close()
            if (task.isSettled) display.recycle()
        }
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

    /** Adapts an ML Kit task; cancelling closes the recognizer, which abandons the task. */
    private class RecognizerTask<T>(private val task: Task<T>, private val recognizer: TextRecognizer) : EngineTask<T> {
        @Volatile var isSettled = false
            private set

        override fun onSettled(listener: (Result<T>) -> Unit) {
            task.addOnCompleteListener(Runnable::run) { completed ->
                isSettled = true
                listener(
                    when {
                        completed.isSuccessful -> Result.success(completed.result)

                        completed.isCanceled -> Result.failure(CancellationException("ML Kit task cancelled"))

                        else -> Result.failure(
                            OcrEngineException("ML Kit recognition failed", completed.exception),
                        )
                    },
                )
            }
        }

        override fun cancel() {
            recognizer.close()
        }
    }
}

class OcrUnsupportedScriptException : IllegalArgumentException("OCR output uses an unsupported script")
