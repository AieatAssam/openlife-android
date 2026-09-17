package org.openlife.vault.repository

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * A stream whose read() blocks until closed from another thread,
     * simulating a provider that never delivers bytes (design §12 /
     * C0-R30's cooperative provider-read deadline: closing the descriptor
     * is what unblocks a stuck read). Deliberately a plain in-memory
     * `InputStream` with no real OS file descriptor involved - an earlier
     * version of this scenario used a real unwritten pipe closed from
     * another thread in an instrumented test, which is a known-hazardous
     * race (a concurrently-closed fd can be reused before the kernel fully
     * unblocks the original read, corrupting an unrelated later file open
     * in the same process). That made a later, unrelated instrumented test
     * hang - found by running the suite repeatedly, not by inspection.
     * This JVM-level equivalent proves the same cooperative-cancellation
     * behaviour with none of that risk.
     */
    private class BlockUntilClosedStream : InputStream() {
        private val latch = CountDownLatch(1)
        private val closed = AtomicBoolean(false)

        override fun read(): Int {
            latch.await(5, TimeUnit.SECONDS)
            if (closed.get()) throw IOException("stream closed while blocked")
            throw AssertionError("test did not close the stream in time")
        }

        override fun close() {
            closed.set(true)
            latch.countDown()
        }
    }

    @Test
    fun closingTheStreamFromAnotherThreadUnblocksABlockedRead() {
        val stream = BlockUntilClosedStream()
        val closer = Thread {
            Thread.sleep(200)
            stream.close()
        }
        closer.start()
        try {
            assertThrows(IOException::class.java) { BoundedStreamReader.read(stream) }
        } finally {
            closer.join(5_000)
            assertTrue("closing thread did not finish", !closer.isAlive)
        }
    }
}
