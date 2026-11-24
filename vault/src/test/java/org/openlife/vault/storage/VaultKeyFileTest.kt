package org.openlife.vault.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.crypto.Envelope
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

    /**
     * P1-18-R10 (V-09): a crash mid-write must never leave a torn
     * database.key. The bytes go to a temporary sibling, are synced, renamed
     * over the target, and the directory is synced; the target itself is
     * never opened for writing.
     */
    @Test
    fun keyFileIsWrittenViaTempAndRename() {
        val dir = java.nio.file.Files.createTempDirectory("openlife-key").toFile()
        val target = File(dir, "database.key")
        val envelope = Envelope(ByteArray(EnvelopeFormat.NONCE_LENGTH_BYTES) { 1 }, ByteArray(48) { 2 })
        val calls = mutableListOf<String>()
        val recording = object : KeyFileOps {
            override fun writeAndSync(file: File, bytes: ByteArray) {
                calls += "write:${file.name}"
                KeyFileOps.Default.writeAndSync(file, bytes)
            }

            override fun rename(from: File, to: File): Boolean {
                calls += "rename:${from.name}->${to.name}"
                return KeyFileOps.Default.rename(from, to)
            }

            override fun syncDirectory(directory: File) {
                calls += "syncDir:${directory.name}"
                KeyFileOps.Default.syncDirectory(directory)
            }
        }

        try {
            VaultKeyFile.writeAndSync(target, envelope, recording)

            assertEquals(
                listOf(
                    "write:database.key.tmp",
                    "rename:database.key.tmp->database.key",
                    "syncDir:${dir.name}",
                ),
                calls,
            )
            assertArrayEquals(EnvelopeCodec.encode(envelope), target.readBytes())
            assertFalse("no temporary file is left behind", File(dir, "database.key.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** P1-18-R10: a temporary key left by an interrupted write is removed; the real key is untouched. */
    @Test
    fun leftoverTemporaryKeyIsRemovedAndTheKeyIsKept() {
        val dir = java.nio.file.Files.createTempDirectory("openlife-key").toFile()
        try {
            val target = File(dir, "database.key").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val temp = File(dir, "database.key.tmp").apply { writeBytes(byteArrayOf(9)) }

            assertTrue(VaultKeyFile.removeLeftoverTemp(target))

            assertFalse(temp.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
            assertTrue("nothing to remove is fine", VaultKeyFile.removeLeftoverTemp(target))
        } finally {
            dir.deleteRecursively()
        }
    }
}
