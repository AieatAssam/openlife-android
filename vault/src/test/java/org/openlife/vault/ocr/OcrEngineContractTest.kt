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

    private class FakeEngine(override val id: String) : OcrEngine {
        override val modelVersion: String = "test"
        val called = AtomicBoolean(false)

        override suspend fun extract(input: OcrEngineInput): OcrEngineOutput {
            called.set(true)
            return OcrEngineOutput(emptyList())
        }
    }
}
