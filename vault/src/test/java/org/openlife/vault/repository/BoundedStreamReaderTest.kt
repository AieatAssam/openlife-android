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
        assertEquals(ImportLimits.MAX_ORIGINAL_BYTES + 1, result.byteCount)
        assertTrue(result.exceededLimit)
        // P1-18 CI addition: an oversized original is rejected on size alone,
        // so none of its plaintext is kept or copied into a final array.
        assertEquals(0, result.bytes.size)
    }

    @Test
    fun exactlyAtLimitReadsWithoutTriggeringTheOverflowSignal() {
        val atLimit = ByteArray(ImportLimits.MAX_ORIGINAL_BYTES.toInt())
        val result = BoundedStreamReader.read(ByteArrayInputStream(atLimit))
        assertEquals(ImportLimits.MAX_ORIGINAL_BYTES, result.bytes.size.toLong())
        assertEquals(false, result.exceededLimit)
    }

    /**
     * P1-18 CI addition (run 45405099804, API 29): the doubling buffer held
     * the old and the new array at once, so an oversized read needed about
     * 24 MiB and then 32 MiB, and hit OutOfMemoryError on a 16 MiB-limit
     * heap. Measured in a forked JVM whose heap is too small for that but
     * large enough for limit + 1 bytes held once.
     */
    @Test
    fun oversizedReadFitsInAHeapThatCannotHoldTwoCopies() {
        val java = java.io.File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-Xmx$PROBE_HEAP",
            "-XX:+UseSerialGC",
            "-cp",
            System.getProperty("java.class.path"),
            BoundedReadPeakProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("probe did not finish", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("probe output: $output", 0, process.exitValue())
        assertTrue(output, output.contains("exceeded=true count=${ImportLimits.MAX_ORIGINAL_BYTES + 1}"))
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

    @Test
    fun deadlineCallbackStopsReadingBetweenChunksWithTypedException() {
        val chunk = ByteArray(64 * 1024)
        val stream = object : InputStream() {
            private var reads = 0

            override fun read(): Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                chunk.copyInto(buffer, offset, 0, minOf(chunk.size, length))
                return minOf(chunk.size, length)
            }
        }
        var deadlineChecks = 0

        val failure = assertThrows(ReadDeadlineExceededException::class.java) {
            BoundedStreamReader.read(stream) {
                deadlineChecks++
                deadlineChecks > 1
            }
        }

        assertTrue(failure.message?.contains("deadline") == true)
        assertTrue("deadline must be checked between chunks", deadlineChecks >= 2)
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

/** 16 MiB + 1 held once, plus JVM baseline, fits; the old ~24-32 MiB peak does not. */
private const val PROBE_HEAP = "22m"

/** Runs in a forked JVM with a small heap; exit code 3 means OutOfMemoryError. */
object BoundedReadPeakProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val zeros = object : InputStream() {
            private var remaining = ImportLimits.MAX_ORIGINAL_BYTES + 4096
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len.toLong(), remaining).toInt()
                java.util.Arrays.fill(b, off, off + n, 0)
                remaining -= n
                return n
            }
        }
        try {
            val result = BoundedStreamReader.read(zeros)
            println("exceeded=${result.exceededLimit} count=${result.byteCount}")
        } catch (error: OutOfMemoryError) {
            println("OOM: $error")
            kotlin.system.exitProcess(3)
        }
    }
}
