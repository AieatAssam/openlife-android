package org.openlife.vault.ocr

import android.graphics.Bitmap
import org.openlife.vault.model.Orientation

/** One recognised text line in the oriented bitmap's pixels, as the native bridge reports it. */
data class RecognizedLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float?)

/** The narrow bridge to native Tesseract, so line mapping can be tested without the native library. */
interface TessRecognizer {
    fun recognize(bitmap: Bitmap): List<RecognizedLine>

    fun stop()

    fun release()
}

/** P2-03 stub. */
object TesseractLineMapper {
    @Suppress("LongParameterList", "UnusedParameter")
    fun toSpans(
        lines: List<RecognizedLine>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Orientation,
    ): List<OcrSpanDraft> = emptyList()
}

/** P2-03 stub. */
object OcrConfidencePolicy {
    fun stored(engineScore: Float?): Float? = engineScore
}

/** P2-03 stub. */
class TesseractOcrEngine(
    private val installer: TessdataInstaller,
    private val events: (String) -> Unit = {},
) : OcrEngine {
    override val id: String = ID
    override val modelVersion: String = MODEL_VERSION

    override suspend fun extract(input: OcrEngineInput): OcrEngineOutput = OcrEngineOutput(emptyList())

    companion object {
        const val ID = "tesseract-eng-fast"
        const val MODEL_VERSION = "t4a-4.9.0+tessdata_fast-4.1.0-7d4322bd"
    }
}
