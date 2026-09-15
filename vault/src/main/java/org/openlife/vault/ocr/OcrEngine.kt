package org.openlife.vault.ocr

import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation

/** Local OCR implementation seam. Implementations must not fetch or upload data. */
interface OcrEngine {
    val id: String
    val modelVersion: String

    suspend fun extract(input: OcrEngineInput): OcrEngineOutput
}

data class OcrEngineInput(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val orientation: Orientation,
    val mimeType: ImageFormat,
) {
    init {
        require(bytes.size.toLong() <= OcrLimits.MAX_SOURCE_BYTES) {
            "OCR source exceeds the byte limit"
        }
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() <= OcrLimits.MAX_SOURCE_PIXELS) {
            "OCR source exceeds the pixel limit"
        }
    }
}

data class OcrEngineOutput(
    val spans: List<OcrSpanDraft>,
)

enum class OcrScriptStatus {
    LATIN,
    UNSUPPORTED,
}

object OcrScriptPolicy {
    fun classify(text: String): OcrScriptStatus {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
            ) {
                return OcrScriptStatus.UNSUPPORTED
            }
            index += Character.charCount(codePoint)
        }
        return OcrScriptStatus.LATIN
    }

    fun classify(spans: List<OcrSpanDraft>): OcrScriptStatus =
        if (spans.any { classify(it.text) == OcrScriptStatus.UNSUPPORTED }) {
            OcrScriptStatus.UNSUPPORTED
        } else {
            OcrScriptStatus.LATIN
        }
}

/** Small explicit registry so later local alternatives can be configured. */
class OcrEngineRegistry(
    engines: List<OcrEngine>,
    selectedEngineId: String,
) {
    private val selectedEngine = engines.singleOrNull { it.id == selectedEngineId }
        ?: throw IllegalArgumentException("No configured local OCR engine named $selectedEngineId")

    fun selected(): OcrEngine = selectedEngine
}
