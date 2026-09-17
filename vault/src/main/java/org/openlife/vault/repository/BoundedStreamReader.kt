package org.openlife.vault.repository

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class BoundedRead(val bytes: ByteArray, val sha256: ByteArray)

class ReadDeadlineExceededException : IOException("provider read deadline exceeded")

/**
 * Reads at most [ImportLimits.MAX_ORIGINAL_BYTES] + 1 bytes from [stream]
 * into bounded memory, computing SHA-256 as bytes arrive (design §11 step
 * 2). Reading exactly limit+1 bytes is the deliberate signal that the
 * original exceeded the limit — [ImageHeaderValidator] rejects on size for
 * exactly that reason, so the caller does not need to special-case it here.
 * The provider's claimed size (e.g. `Content-Length`-equivalent metadata)
 * is never consulted; only what is actually read counts.
 */
object BoundedStreamReader {

    private const val CHUNK_SIZE = 64 * 1024

    fun read(stream: InputStream, deadline: () -> Boolean = { false }): BoundedRead {
        val digest = MessageDigest.getInstance("SHA-256")
        val limit = ImportLimits.MAX_ORIGINAL_BYTES + 1
        val buffer = ByteArray(CHUNK_SIZE)
        val out = WipeableByteArrayOutputStream()
        var total = 0L

        try {
            while (total < limit) {
                if (deadline()) throw ReadDeadlineExceededException()
                val toRead = minOf(buffer.size.toLong(), limit - total).toInt()
                val n = stream.read(buffer, 0, toRead)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                total += n
            }
            return BoundedRead(out.toByteArray(), digest.digest())
        } finally {
            buffer.fill(0)
            out.clear()
        }
    }

    private class WipeableByteArrayOutputStream(initialCapacity: Int = CHUNK_SIZE) {
        private var buffer = ByteArray(initialCapacity)
        private var size = 0

        fun write(source: ByteArray, offset: Int, length: Int) {
            ensureCapacity(size + length)
            source.copyInto(buffer, size, offset, offset + length)
            size += length
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)

        fun clear() {
            buffer.fill(0)
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) return
            var nextSize = buffer.size
            while (nextSize < required) {
                nextSize = (nextSize * 2).coerceAtMost(ImportLimits.MAX_ORIGINAL_BYTES.toInt() + 1)
            }
            val replacement = buffer.copyOf(nextSize)
            buffer.fill(0)
            buffer = replacement
        }
    }
}
