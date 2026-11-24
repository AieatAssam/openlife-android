package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-18 review addition (recorded in P1-19 notes): an OutOfMemoryError while
 * decoding or orienting a preview, viewer image or thumbnail surfaces as the
 * existing "no bitmap" result (Unreadable / Unavailable / no thumbnail),
 * never a crash, and never leaves the half-built bitmap allocated.
 */
class BitmapOomGuardTest {
    private val recycled = mutableListOf<String>()

    @Test
    fun outOfMemoryWhileDecodingIsNoBitmap() {
        val result = decodeGuardingMemory<String>(
            decode = { throw OutOfMemoryError("synthetic") },
            orient = { it },
            recycle = { recycled += it },
        )
        assertNull(result)
        assertEquals(emptyList<String>(), recycled)
    }

    @Test
    fun outOfMemoryWhileOrientingRecyclesTheDecodedBitmap() {
        val result = decodeGuardingMemory<String>(
            decode = { "sampled" },
            orient = { throw OutOfMemoryError("synthetic") },
            recycle = { recycled += it },
        )
        assertNull(result)
        assertEquals(listOf("sampled"), recycled)
    }

    @Test
    fun aGoodDecodeRecyclesOnlyTheReplacedBitmap() {
        assertEquals("upright", decodeGuardingMemory<String>({ "sampled" }, { "upright" }, { recycled += it }))
        assertEquals(listOf("sampled"), recycled)
        recycled.clear()
        assertEquals("same", decodeGuardingMemory<String>({ "same" }, { it }, { recycled += it }))
        assertEquals(emptyList<String>(), recycled)
    }
}
