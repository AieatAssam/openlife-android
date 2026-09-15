package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrSpanValidationTest {
    @Test
    fun `confidence and evidence may be absent without fabrication`() {
        val span = OcrSpanDraft(text = "hello", confidence = null, region = null)

        val validated = OcrOutputValidator.validate(listOf(span)).single()

        assertEquals("hello", validated.text)
        assertNull(validated.confidence)
        assertNull(validated.region)
    }

    @Test
    fun `confidence must be finite and in the engine's range`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrSpanDraft("hello", confidence = -0.01f, region = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrSpanDraft("hello", confidence = 1.01f, region = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrSpanDraft("hello", confidence = Float.NaN, region = null)
        }
    }

    @Test
    fun `evidence region is a complete nonnegative rectangle`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrEvidenceRegion(left = 5, top = 1, right = 4, bottom = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrEvidenceRegion(left = -1, top = 0, right = 4, bottom = 2)
        }
    }
}
