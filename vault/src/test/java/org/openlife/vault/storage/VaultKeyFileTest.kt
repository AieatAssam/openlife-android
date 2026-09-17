package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeFormat
import java.io.File

class VaultKeyFileTest {

    @Test
    fun oversizedKeyFileIsMalformedNotAllocated() {
        val file = File.createTempFile("openlife-oversized-key", ".key")
        val maximumEncodedLength =
            EnvelopeFormat.MAX_CIPHERTEXT_LENGTH.toLong() +
                EnvelopeFormat.HEADER_LENGTH_BYTES +
                EnvelopeFormat.NONCE_LENGTH_BYTES +
                EnvelopeFormat.CIPHERTEXT_LENGTH_FIELD_BYTES
        java.io.RandomAccessFile(file, "rw").use { it.setLength(maximumEncodedLength + 1) }

        var reads = 0
        val reader = BoundedFileReader(
            BoundedFileOps { _, _ ->
                reads++
                error("oversized key file must not be read")
            },
        )

        try {
            val failure = org.junit.Assert.assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
                VaultKeyFile.read(file, reader)
            }
            assertEquals(true, failure.message?.contains("length"))
            assertEquals(0, reads)
        } finally {
            file.delete()
        }
    }
}
