package org.openlife.vault.repository

import org.openlife.vault.crypto.AesGcmCodec
import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeAad
import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.EnvelopeFormat
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.Source
import org.openlife.vault.storage.BoundedFileReader
import org.openlife.vault.storage.FileTooLargeException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

fun interface SourceEnvelopeDecryptor {
    fun decrypt(source: Source, envelope: Envelope): ByteArray
}

/**
 * Shared by [ImportRepository] (Save-time recheck and stage/blob preview),
 * [RecoveryRepository] (startup authentication of every READY blob), and
 * [SourceViewRepository] (the viewer): unwraps a Source's DEK, decrypts its
 * artefact file, and confirms the plaintext still matches the digest
 * recorded at Prepare time. [decryptAndVerify] is the one place that
 * actually releases plaintext; [authenticates] is a convenience for callers
 * that only need a yes/no.
 */
class ArtefactAuthenticator internal constructor(
    private val decryptor: SourceEnvelopeDecryptor,
    private val fileReader: BoundedFileReader,
) {

    constructor(
        keystoreWrapper: KeystoreWrapper,
        fileReader: BoundedFileReader = BoundedFileReader.Default,
    ) : this(
        decryptor = SourceEnvelopeDecryptor { source, envelope ->
            val dek = keystoreWrapper.unwrap(
                EnvelopeCodec.decode(source.wrappedDek!!),
                EnvelopeDomain.SOURCE_KEY,
                source.id,
            )
            try {
                AesGcmCodec.decrypt(
                    envelope,
                    SecretKeySpec(dek, "AES"),
                    EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, source.id),
                )
            } finally {
                dek.fill(0)
            }
        },
        fileReader = fileReader,
    )

    fun authenticates(source: Source, artefactFile: File): Boolean {
        val plaintext = decryptAndVerify(source, artefactFile) ?: return false
        plaintext.fill(0)
        return true
    }

    /** Returns the authenticated, digest-verified plaintext, or null on any failure. */
    fun decryptAndVerify(source: Source, artefactFile: File): ByteArray? {
        if (!artefactFile.exists()) return null
        var plaintext: ByteArray? = null
        return try {
            val envelope = EnvelopeCodec.decode(
                fileReader.readAtMost(
                    artefactFile,
                    EnvelopeFormat.MAX_ENCODED_LENGTH_BYTES,
                ),
            )
            plaintext = decryptor.decrypt(source, envelope)
            if (MessageDigest.isEqual(source.sha256, MessageDigest.getInstance("SHA-256").digest(plaintext))) {
                val result = plaintext
                plaintext = null
                result
            } else {
                null
            }
        } catch (_: FileTooLargeException) {
            null
        } catch (_: EnvelopeAuthenticationException) {
            null
        } catch (_: EnvelopeCodec.MalformedEnvelopeException) {
            null
        } catch (_: IOException) {
            null
        } finally {
            plaintext?.fill(0)
        }
    }
}
