package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrOutputLimitsTest {
    @Test
    fun `limits include inherited input ceilings and derived output ceilings`() {
        assertEquals(16L * 1024 * 1024, OcrLimits.MAX_SOURCE_BYTES)
        assertEquals(40_000_000L, OcrLimits.MAX_SOURCE_PIXELS)
        assertEquals(4_000_000L, OcrLimits.MAX_DECODE_PIXELS)
        assertEquals(15_000L, OcrLimits.DEADLINE_MILLIS)
        assertEquals(200_000, OcrLimits.MAX_TEXT_CHARS)
        assertEquals(2_000, OcrLimits.MAX_SPANS)
    }

    @Test
    fun `output validator rejects an overlarge revision`() {
        val spans = List(OcrLimits.MAX_SPANS + 1) { index ->
            OcrEngineSpan(text = "s$index", confidence = null, region = null)
        }

        assertThrows(OcrLimitExceededException::class.java) {
            OcrOutputValidator.validate(spans)
        }
    }

    @Test
    fun `output validator counts characters across spans`() {
        val spans = listOf(
            OcrEngineSpan("a".repeat(OcrLimits.MAX_TEXT_CHARS), null, null),
            OcrEngineSpan("b", null, null),
        )

        assertThrows(OcrLimitExceededException::class.java) {
            OcrOutputValidator.validate(spans)
        }
    }
}
