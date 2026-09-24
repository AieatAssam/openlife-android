package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import java.util.concurrent.atomic.AtomicBoolean

class OcrEngineContractTest {
    @Test
    fun `registry resolves the configured local engine`() {
        val first = FakeEngine("first")
        val second = FakeEngine("second")

        val registry = OcrEngineRegistry(listOf(first, second), selectedEngineId = "second")

        assertSame(second, registry.selected())
    }

    @Test
    fun `registry rejects an unknown engine instead of falling back`() {
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OcrEngineRegistry(listOf(FakeEngine("first")), selectedEngineId = "missing")
        }
        assertTrue(error.message!!.contains("missing"))
    }

    @Test
    fun `script policy accepts Latin and abstains on non Latin letters`() {
        assertEquals(OcrScriptStatus.LATIN, OcrScriptPolicy.classify("Café 2026"))
        assertEquals(OcrScriptStatus.UNSUPPORTED, OcrScriptPolicy.classify("hello мир"))
    }

    @Test
    fun `engine input carries bounded source metadata without a provider URI`() {
        val input = OcrEngineInput(
            bytes = byteArrayOf(1, 2, 3),
            width = 10,
            height = 20,
            orientation = Orientation.NORMAL,
            mimeType = ImageFormat.JPEG,
        )

        assertEquals(10, input.width)
        assertEquals(ImageFormat.JPEG, input.mimeType)
    }

    /** P2-03-R2 through the fake-free bridge: Tesseract lines become spans in source pixels. */
    @Test
    fun `tesseract lines map to source pixel spans and blank lines are dropped`() {
        // Source 100x60 stored ROTATE_90, so the oriented bitmap is 60x100 (see OrientedOcrRegionTest).
        val spans = TesseractLineMapper.toSpans(
            lines = listOf(
                RecognizedLine("   ", 0, 0, 10, 10, 91f),
                RecognizedLine("TOTAL 42.50", 20, 10, 40, 30, 88f),
            ),
            bitmapWidth = 60,
            bitmapHeight = 100,
            sourceWidth = 100,
            sourceHeight = 60,
            orientation = Orientation.ROTATE_90,
        )

        assertEquals(listOf("TOTAL 42.50"), spans.map { it.text })
        assertEquals(OcrEvidenceRegion(10, 20, 30, 40), spans.single().region)
        assertEquals("no uncalibrated score is stored (R6)", null, spans.single().confidence)
    }

    /** P2-03-R6: until P2-04 shows a monotonic score/CER relation, no engine stores a confidence. */
    @Test
    fun `no engine stores an uncalibrated confidence`() {
        assertEquals(null, OcrConfidencePolicy.stored(0.93f))
        assertEquals(null, OcrConfidencePolicy.stored(88f))
        assertEquals(null, OcrConfidencePolicy.stored(null))
    }

    private class FakeEngine(override val id: String) : OcrEngine {
        override val modelVersion: String = "test"
        val called = AtomicBoolean(false)

        override suspend fun extract(input: OcrEngineInput): OcrEngineOutput {
            called.set(true)
            return OcrEngineOutput(emptyList())
        }
    }
}
