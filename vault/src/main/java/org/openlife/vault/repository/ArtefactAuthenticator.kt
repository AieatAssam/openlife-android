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
 * Shared by [ImportRepository.saveImport] (Save-time recheck) and
 * [RecoveryRepository] (startup authentication of every READY blob):
 * unwraps a Source's DEK, decrypts its artefact file, and confirms the
 * plaintext still matches the digest recorded at Prepare time. Never
 * returns plaintext to the caller - only whether it authenticated - since
 * neither caller needs the bytes themselves.
 */
class ArtefactAuthenticator(private val keystoreWrapper: KeystoreWrapper) {

    fun authenticates(source: Source, artefactFile: File): Boolean {
        if (!artefactFile.exists()) return false
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
                MessageDigest.isEqual(source.sha256, MessageDigest.getInstance("SHA-256").digest(plaintext))
            } finally {
                dek.fill(0)
            }
        } catch (e: EnvelopeAuthenticationException) {
            false
        } catch (e: EnvelopeCodec.MalformedEnvelopeException) {
            false
        } catch (e: IOException) {
            false
        }
    }
}
