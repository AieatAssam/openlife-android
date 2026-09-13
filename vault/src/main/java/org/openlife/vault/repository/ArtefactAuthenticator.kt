package org.openlife.vault.repository

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import org.openlife.vault.crypto.AesGcmCodec
import org.openlife.vault.crypto.EnvelopeAad
import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.Source

/**
 * Shared by [ImportRepository] (Save-time recheck and stage/blob preview),
 * [RecoveryRepository] (startup authentication of every READY blob), and
 * [SourceViewRepository] (the viewer): unwraps a Source's DEK, decrypts its
 * artefact file, and confirms the plaintext still matches the digest
 * recorded at Prepare time. [decryptAndVerify] is the one place that
 * actually releases plaintext; [authenticates] is a convenience for callers
 * that only need a yes/no.
 */
class ArtefactAuthenticator(private val keystoreWrapper: KeystoreWrapper) {

    fun authenticates(source: Source, artefactFile: File): Boolean =
        decryptAndVerify(source, artefactFile) != null

    /** Returns the authenticated, digest-verified plaintext, or null on any failure. */
    fun decryptAndVerify(source: Source, artefactFile: File): ByteArray? {
        if (!artefactFile.exists()) return null
        return try {
            val dek = keystoreWrapper.unwrap(
                EnvelopeCodec.decode(source.wrappedDek!!),
                EnvelopeDomain.SOURCE_KEY,
                source.id
            )
            try {
                val envelope = EnvelopeCodec.decode(artefactFile.readBytes())
                val plaintext = AesGcmCodec.decrypt(
                    envelope,
                    SecretKeySpec(dek, "AES"),
                    EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, source.id)
                )
                if (MessageDigest.isEqual(source.sha256, MessageDigest.getInstance("SHA-256").digest(plaintext))) {
                    plaintext
                } else {
                    null
                }
            } finally {
                dek.fill(0)
            }
        } catch (e: EnvelopeAuthenticationException) {
            null
        } catch (e: EnvelopeCodec.MalformedEnvelopeException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
