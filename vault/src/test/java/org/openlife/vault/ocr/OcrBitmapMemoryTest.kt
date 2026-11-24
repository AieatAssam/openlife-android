package org.openlife.vault.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * P1-18 review addition (recorded in P1-19 notes): OcrRepository maps
 * Exceptions to a typed ENGINE_FAILURE, but an OutOfMemoryError from the OCR
 * decode or the upright copy is an Error and escaped as a crash. It now
 * surfaces as [OcrDecodeException], and a decoded bitmap whose upright copy
 * failed is recycled.
 */
class OcrBitmapMemoryTest {
    private val recycled = mutableListOf<String>()

    @Test
    fun outOfMemoryWhileDecodingIsADecodeFailure() {
        assertThrows(OcrDecodeException::class.java) {
            OcrBitmapPreparer.prepareGuarded<String>(
                decode = { throw OutOfMemoryError("synthetic") },
                orient = { it },
                recycle = { recycled += it },
            )
        }
        assertEquals(emptyList<String>(), recycled)
    }

    @Test
    fun outOfMemoryWhileOrientingRecyclesAndIsADecodeFailure() {
        assertThrows(OcrDecodeException::class.java) {
            OcrBitmapPreparer.prepareGuarded<String>(
                decode = { "decoded" },
                orient = { throw OutOfMemoryError("synthetic") },
                recycle = { recycled += it },
            )
        }
        assertEquals(listOf("decoded"), recycled)
    }

    @Test
    fun aGoodDecodeRecyclesOnlyTheReplacedBitmap() {
        val upright = OcrBitmapPreparer.prepareGuarded<String>(
            decode = { "decoded" },
            orient = { "upright" },
            recycle = { recycled += it },
        )
        assertEquals("upright", upright)
        assertEquals(listOf("decoded"), recycled)
    }

    @Test
    fun aRunWithAnOutOfMemoryDecodeEndsAsAnEngineFailure() {
        assertEquals(
            org.openlife.vault.ocr.OcrFailureReason.ENGINE_FAILURE,
            OcrRunStateMachine.reasonFor(OcrDecodeException("synthetic")),
        )
    }
}
