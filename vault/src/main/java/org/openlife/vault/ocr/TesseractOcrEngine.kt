package org.openlife.vault.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.OrientationTransform
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.ceil

/** One recognised text line in the oriented bitmap's pixels, as the native bridge reports it. */
data class RecognizedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float?,
)

/** The narrow bridge to native Tesseract, so line mapping can be tested without the native library. */
interface TessRecognizer {
    /** [onRecognizing] fires once the cancellable recognition phase has begun. */
    fun recognize(bitmap: Bitmap, onRecognizing: () -> Unit): List<RecognizedLine>

    /** Ends a recognition in progress; safe to call from another thread. */
    fun stop()

    /** Frees native state. Call only once [recognize] has returned. */
    fun release()
}

/** P2-03-R2: Tesseract's text lines become spans with regions in source pixels. */
object TesseractLineMapper {
    @Suppress("LongParameterList")
    fun toSpans(
        lines: List<RecognizedLine>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Orientation,
    ): List<OcrSpanDraft> {
        // The engine saw the upright image, possibly sampled down; scale back to upright source pixels.
        val displayWidth = OrientationTransform.displayWidth(sourceWidth, sourceHeight, orientation)
        val displayHeight = OrientationTransform.displayHeight(sourceWidth, sourceHeight, orientation)
        val scaleX = displayWidth.toDouble() / bitmapWidth
        val scaleY = displayHeight.toDouble() / bitmapHeight
        return lines.mapNotNull { line ->
            // Tesseract ends lines with a newline and can report blank lines; spans are never empty.
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val left = (line.left * scaleX).toInt().coerceIn(0, displayWidth)
            val top = (line.top * scaleY).toInt().coerceIn(0, displayHeight)
            val right = ceil(line.right * scaleX).toInt().coerceIn(left, displayWidth)
            val bottom = ceil(line.bottom * scaleY).toInt().coerceIn(top, displayHeight)
            OcrSpanDraft(
                text = text,
                confidence = OcrConfidencePolicy.stored(line.score),
                region = OcrCoordinateMapper.toSourcePixels(
                    OcrEvidenceRegion(left, top, right, bottom),
                    sourceWidth,
                    sourceHeight,
                    orientation,
                ),
            )
        }
    }
}

/**
 * P2-03-R6 / design §4: an engine's score is stored only once P2-04 shows it
 * relates monotonically to error rate. Until then no engine stores one, and
 * the UI never shows a percentage.
 */
object OcrConfidencePolicy {
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter")
    fun stored(engineScore: Float?): Float? = null
}

/**
 * Open-source, offline OCR (ADR-0003): tesseract4android with the vendored
 * tessdata_fast English model, LSTM engine, automatic page segmentation.
 *
 * Recognition runs on its own thread. Cancelling calls Tesseract's stop(),
 * which ends the recognition phase within about a second; layout analysis
 * before it cannot be interrupted. Native state is freed and the bitmap
 * recycled only after the native call returns: if the caller stops waiting
 * first (P2-02's bounded settle), the task frees them itself when it
 * returns, never while Tesseract is still using them (P2-02-R4). The
 * deadline is the repository's.
 */
class TesseractOcrEngine(
    private val installer: TessdataInstaller,
    private val events: (String) -> Unit = {},
    private val recognizerFactory: (File) -> TessRecognizer = ::NativeTessRecognizer,
) : OcrEngine {
    override val id: String = ID
    override val modelVersion: String = MODEL_VERSION

    override suspend fun extract(input: OcrEngineInput): OcrEngineOutput {
        val bitmap = OcrBitmapPreparer.prepare(input)
        val recognizer = openRecognizer(bitmap)
        val task = RecognitionTask(recognizer, bitmap, events)
        try {
            val lines = awaitEngineTask(task)
            val spans = TesseractLineMapper.toSpans(
                lines,
                bitmap.width,
                bitmap.height,
                input.width,
                input.height,
                input.orientation,
            )
            OcrOutputValidator.validate(spans)
            if (OcrScriptPolicy.classify(spans) == OcrScriptStatus.UNSUPPORTED) throw OcrUnsupportedScriptException()
            return OcrEngineOutput(spans)
        } finally {
            task.releaseWhenSettled {
                recognizer.release()
                bitmap.recycle()
                events("recycled")
            }
        }
    }

    /** Installing (a 4 MB copy and digest) and loading the model are disk work; a failure frees [bitmap]. */
    private suspend fun openRecognizer(bitmap: Bitmap): TessRecognizer {
        var opened: TessRecognizer? = null
        try {
            opened = withContext(Dispatchers.IO) { recognizerFactory(installer.install()) }
            return opened
        } catch (failure: IOException) {
            throw OcrEngineException("the OCR model is unavailable", failure)
        } finally {
            if (opened == null) bitmap.recycle()
        }
    }

    /** Runs one recognition on its own thread; cancelling stops it natively. */
    private class RecognitionTask(
        private val recognizer: TessRecognizer,
        private val bitmap: Bitmap,
        private val events: (String) -> Unit,
    ) : EngineTask<List<RecognizedLine>> {
        private val lock = Any()
        private var settled = false
        private var pendingRelease: (() -> Unit)? = null

        @Volatile private var stopped = false

        /** Runs [release] now if the native call has returned, otherwise as soon as it does. */
        fun releaseWhenSettled(release: () -> Unit) {
            val now = synchronized(lock) {
                if (!settled) pendingRelease = release
                settled
            }
            if (now) release()
        }

        override fun onSettled(listener: (Result<List<RecognizedLine>>) -> Unit) {
            thread(name = "openlife-tesseract", isDaemon = true) {
                events("recognition-started")
                val outcome = runCatching { recognizer.recognize(bitmap) { events("recognizing") } }
                val release = synchronized(lock) {
                    settled = true
                    pendingRelease
                }
                events("settled")
                release?.invoke()
                listener(
                    when {
                        stopped -> Result.failure(CancellationException("Tesseract recognition stopped"))

                        outcome.isFailure -> Result.failure(
                            OcrEngineException("Tesseract recognition failed", outcome.exceptionOrNull()),
                        )

                        else -> outcome
                    },
                )
            }
        }

        override fun cancel() {
            stopped = true
            events("stop")
            recognizer.stop()
        }
    }

    companion object {
        const val ID = "tesseract-eng-fast"

        /** Library, model tag and model digest prefix: enough to reproduce a revision's text. */
        const val MODEL_VERSION = "t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd"
    }
}

/**
 * The JNI bridge. The API is created with a progress notifier because
 * tesseract4android honours stop() only while a monitor is attached, which
 * getHOCRText uses; the text lines are then read from the result iterator.
 */
internal class NativeTessRecognizer(dataPath: File) : TessRecognizer {
    @Volatile private var stopped = false

    @Volatile private var onRecognizing: (() -> Unit)? = null
    private val api = TessBaseAPI { _ ->
        // Progress is reported only from the recognition loop, which is where stop() takes effect.
        onRecognizing?.invoke()
        onRecognizing = null
    }

    init {
        if (!api.init(dataPath.absolutePath, TessdataInstaller.LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            throw OcrEngineException("Tesseract could not load its model")
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
    }

    override fun recognize(bitmap: Bitmap, onRecognizing: () -> Unit): List<RecognizedLine> {
        if (stopped) return emptyList()
        this.onRecognizing = onRecognizing
        api.setImage(bitmap)
        api.getHOCRText(0)
        if (stopped) return emptyList()
        val iterator = api.resultIterator ?: return emptyList()
        val lines = mutableListOf<RecognizedLine>()
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(LEVEL)
                if (text != null) {
                    val box = iterator.getBoundingRect(LEVEL)
                    lines += RecognizedLine(text, box.left, box.top, box.right, box.bottom, iterator.confidence(LEVEL))
                }
            } while (iterator.next(LEVEL))
        } finally {
            iterator.delete()
        }
        return lines
    }

    override fun stop() {
        stopped = true
        api.stop()
    }

    override fun release() {
        api.recycle()
    }

    private companion object {
        const val LEVEL = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
    }
}
