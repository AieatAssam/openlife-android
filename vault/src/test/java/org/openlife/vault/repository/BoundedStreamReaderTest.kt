package org.openlife.vault.repository

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedStreamReaderTest {

    @Test
    fun readsAllBytesAndComputesMatchingDigestForSmallInput() {
        val original = "hello openlife".toByteArray()
        val result = BoundedStreamReader.read(ByteArrayInputStream(original))
        assertArrayEquals(original, result.bytes)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(original), result.sha256)
    }

    @Test
    fun stopsAtLimitPlusOneForOversizedInput() {
        val oversized = ByteArray((ImportLimits.MAX_ORIGINAL_BYTES + 1000).toInt())
        val result = BoundedStreamReader.read(ByteArrayInputStream(oversized))
        assertEquals(ImportLimits.MAX_ORIGINAL_BYTES + 1, result.bytes.size.toLong())
    }

    @Test
    fun exactlyAtLimitReadsWithoutTriggeringTheOverflowSignal() {
        val atLimit = ByteArray(ImportLimits.MAX_ORIGINAL_BYTES.toInt())
        val result = BoundedStreamReader.read(ByteArrayInputStream(atLimit))
        assertEquals(ImportLimits.MAX_ORIGINAL_BYTES, result.bytes.size.toLong())
    }

    @Test
    fun ignoresAnyClaimedLengthAndReadsWhatIsActuallyThere() {
        // A stream with fewer bytes than any declared/expected size must not
        // be padded or treated as an error by the reader itself - it simply
        // reads what is available. Format/size rejection is a separate step
        // (ImageHeaderValidator), not this reader's job.
        val short = "x".toByteArray()
        val result = BoundedStreamReader.read(ByteArrayInputStream(short))
        assertEquals(1, result.bytes.size)
    }
}
