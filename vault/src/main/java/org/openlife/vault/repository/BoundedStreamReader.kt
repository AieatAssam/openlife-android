package org.openlife.vault.repository

import java.io.InputStream
import java.security.MessageDigest

data class BoundedRead(val bytes: ByteArray, val sha256: ByteArray)

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

    fun read(stream: InputStream): BoundedRead {
        val digest = MessageDigest.getInstance("SHA-256")
        val limit = ImportLimits.MAX_ORIGINAL_BYTES + 1
        val buffer = ByteArray(CHUNK_SIZE)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L

        while (total < limit) {
            val toRead = minOf(buffer.size.toLong(), limit - total).toInt()
            val n = stream.read(buffer, 0, toRead)
            if (n < 0) break
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            total += n
        }

        return BoundedRead(out.toByteArray(), digest.digest())
    }
}
