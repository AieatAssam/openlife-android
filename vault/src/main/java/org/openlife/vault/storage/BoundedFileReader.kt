package org.openlife.vault.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

private const val BOUNDED_READ_CHUNK_SIZE = 64 * 1024

/** Low-level file read seam used by bounded envelope readers and their tests. */
fun interface BoundedFileOps {
    fun read(file: File, maxBytes: Long): ByteArray

    companion object {
        val Default = BoundedFileOps { file, maxBytes ->
            val output = WipeableByteAccumulator(BOUNDED_READ_CHUNK_SIZE)
            val buffer = ByteArray(BOUNDED_READ_CHUNK_SIZE)
            var total = 0L
            try {
                FileInputStream(file).use { input ->
                    while (total <= maxBytes) {
                        val requested = minOf(buffer.size.toLong(), maxBytes + 1 - total).toInt()
                        if (requested == 0) {
                            total = maxBytes + 1
                        } else {
                            val count = input.read(buffer, 0, requested)
                            when {
                                count < 0 -> total = maxBytes + 1

                                count == 0 -> throw IOException("file read returned no bytes")

                                else -> {
                                    output.write(buffer, 0, count)
                                    total += count
                                    if (total > maxBytes) throw FileTooLargeException(maxBytes)
                                }
                            }
                        }
                    }
                }
                return@BoundedFileOps output.toByteArray()
            } finally {
                buffer.fill(0)
                output.clear()
            }
        }
    }
}

class FileTooLargeException(val maxBytes: Long) :
    IOException("file length exceeds the bounded read limit of $maxBytes bytes")

/** Checks the file length before opening it and bounds the actual read too. */
class BoundedFileReader(private val fileOps: BoundedFileOps = BoundedFileOps.Default) {

    fun readAtMost(file: File, maxBytes: Long): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        if (!file.exists()) throw FileNotFoundException(file.path)
        if (file.length() > maxBytes) failForLength(maxBytes)
        return fileOps.read(file, maxBytes).also { bytes ->
            if (bytes.size.toLong() > maxBytes) failForLength(maxBytes)
        }
    }

    companion object {
        val Default = BoundedFileReader()
    }

    private fun failForLength(maxBytes: Long): Nothing = throw FileTooLargeException(maxBytes)
}

private class WipeableByteAccumulator(initialCapacity: Int) {
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
            nextSize = (nextSize * 2).coerceAtMost(required)
        }
        val replacement = buffer.copyOf(nextSize)
        buffer.fill(0)
        buffer = replacement
    }
}
