package org.openlife.vault.repository

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class BoundedRead(
    val bytes: ByteArray,
    val sha256: ByteArray,
    /** How many bytes the provider actually delivered (at most limit + 1). */
    val byteCount: Long = bytes.size.toLong(),
) {
    /** True when limit + 1 bytes arrived: the original exceeds [ImportLimits.MAX_ORIGINAL_BYTES]. */
    val exceededLimit: Boolean get() = byteCount > ImportLimits.MAX_ORIGINAL_BYTES
}

class ReadDeadlineExceededException : IOException("provider read deadline exceeded")

/**
 * Reads at most [ImportLimits.MAX_ORIGINAL_BYTES] + 1 bytes from [stream]
 * into bounded memory, computing SHA-256 as bytes arrive (design §11 step
 * 2). Reading exactly limit+1 bytes is the deliberate signal that the
 * original exceeded the limit; the result then has [BoundedRead.exceededLimit]
 * set and carries no bytes.
 * The provider's claimed size (e.g. `Content-Length`-equivalent metadata)
 * is never consulted; only what is actually read counts.
 */
object BoundedStreamReader {

    private const val CHUNK_SIZE = 64 * 1024

    /**
     * Accumulates fixed-size chunks rather than a doubling buffer, which held
     * the old and the new array at once (about 24 MiB, then 32 MiB, for an
     * oversized read: OutOfMemoryError on a 16 MiB-limit heap, CI run
     * 45405099804). Peak: the chunks, plus one exact-size copy for an
     * accepted original (2N); an oversized read is rejected on size alone,
     * so its chunks are wiped and no copy is made (N + 1 chunk). Every chunk
     * is wiped after use.
     */
    fun read(
        stream: InputStream,
        deadline: () -> Boolean = { false },
        newChunk: (Int) -> ByteArray = ::ByteArray,
    ): BoundedRead {
        val digest = MessageDigest.getInstance("SHA-256")
        val limit = ImportLimits.MAX_ORIGINAL_BYTES + 1
        val chunks = ArrayList<ByteArray>()
        var total = 0L

        try {
            while (total < limit) {
                if (deadline()) throw ReadDeadlineExceededException()
                val chunk = chunks.lastOrNull()?.takeIf { (total % CHUNK_SIZE).toInt() != 0 }
                    ?: newChunk(CHUNK_SIZE).also { chunks += it }
                val offset = (total % CHUNK_SIZE).toInt()
                val toRead = minOf((CHUNK_SIZE - offset).toLong(), limit - total).toInt()
                val n = stream.read(chunk, offset, toRead)
                if (n < 0) break
                digest.update(chunk, offset, n)
                total += n
            }
            if (total >= limit) {
                // Exceeded: the bytes are never needed, only the count.
                return BoundedRead(ByteArray(0), digest.digest(), byteCount = total)
            }
            val bytes = ByteArray(total.toInt())
            var copied = 0
            for (chunk in chunks) {
                val length = minOf(CHUNK_SIZE, bytes.size - copied)
                if (length <= 0) break
                chunk.copyInto(bytes, copied, 0, length)
                copied += length
                chunk.fill(0)
            }
            return BoundedRead(bytes, digest.digest())
        } finally {
            chunks.forEach { it.fill(0) }
            chunks.clear()
        }
    }
}
