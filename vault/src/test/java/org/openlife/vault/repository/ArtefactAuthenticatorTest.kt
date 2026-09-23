package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.vault.crypto.EnvelopeFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.BoundedFileOps
import org.openlife.vault.storage.BoundedFileReader
import java.io.File
import java.util.UUID

class ArtefactAuthenticatorTest {

    @Test
    fun oversizedArtefactFileFailsClosedWithoutReadingIt() {
        val file = File.createTempFile("openlife-oversized-artefact", ".blob")
        val maximumEncodedLength =
            EnvelopeFormat.MAX_CIPHERTEXT_LENGTH.toLong() +
                EnvelopeFormat.HEADER_LENGTH_BYTES +
                EnvelopeFormat.NONCE_LENGTH_BYTES +
                EnvelopeFormat.CIPHERTEXT_LENGTH_FIELD_BYTES
        java.io.RandomAccessFile(file, "rw").use { it.setLength(maximumEncodedLength + 1) }

        var reads = 0
        val authenticator = ArtefactAuthenticator(
            decryptor = { _, _ -> ByteArray(0) },
            fileReader = BoundedFileReader(
                BoundedFileOps { _, _ ->
                    reads++
                    error("oversized artefact must not be read")
                },
            ),
        )

        try {
            val source = Source(
                id = UUID.randomUUID(),
                state = SourceState.READY,
                importedAt = 1L,
                intakeKind = IntakeKind.SHARE,
                mimeType = null,
                byteCount = null,
                sha256 = null,
                width = null,
                height = null,
                orientation = null,
                wrappedDek = null,
                artefactVersion = null,
            )

            assertNull(authenticator.decryptAndVerify(source, file))
            assertEquals(0, reads)
        } finally {
            file.delete()
        }
    }
}
