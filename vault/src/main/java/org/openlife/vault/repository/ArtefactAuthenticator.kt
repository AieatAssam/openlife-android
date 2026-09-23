package org.openlife.vault.repository

import org.openlife.vault.crypto.AesGcmCodec
import org.openlife.vault.crypto.Envelope
import org.openlife.vault.crypto.EnvelopeAad
import org.openlife.vault.crypto.EnvelopeAuthenticationException
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.EnvelopeDomain
import org.openlife.vault.crypto.EnvelopeFormat
import org.openlife.vault.crypto.KeystoreUnavailableException
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
        val result = check(source, artefactFile) as? ArtefactCheck.Verified ?: return false
        result.plaintext.fill(0)
        return true
    }

    /** Returns the authenticated, digest-verified plaintext, or null on any failure. */
    fun decryptAndVerify(source: Source, artefactFile: File): ByteArray? =
        (check(source, artefactFile) as? ArtefactCheck.Verified)?.plaintext

    /**
     * Authenticates [artefactFile] for [source] and says why it failed. Only
     * [ArtefactCheck.Corrupt] is evidence about the stored bytes; a Keystore
     * or I/O failure is [ArtefactCheck.Transient] (P1-13-R5).
     */
    fun check(source: Source, artefactFile: File): ArtefactCheck {
        if (!artefactFile.exists()) return ArtefactCheck.Missing
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
                val verified = ArtefactCheck.Verified(plaintext)
                plaintext = null
                verified
            } else {
                ArtefactCheck.Corrupt
            }
        } catch (_: FileTooLargeException) {
            ArtefactCheck.Corrupt
        } catch (_: EnvelopeAuthenticationException) {
            ArtefactCheck.Corrupt
        } catch (_: EnvelopeCodec.MalformedEnvelopeException) {
            ArtefactCheck.Corrupt
        } catch (_: KeystoreUnavailableException) {
            ArtefactCheck.Transient
        } catch (_: IOException) {
            ArtefactCheck.Transient
        } finally {
            plaintext?.fill(0)
        }
    }
}

/** Why an artefact did or did not authenticate. */
sealed interface ArtefactCheck {
    /** Authenticated plaintext whose digest matches; the caller owns and clears it. */
    class Verified(val plaintext: ByteArray) : ArtefactCheck

    /** The stored envelope or digest failed: tampered, truncated or damaged content. */
    data object Corrupt : ArtefactCheck

    /** The artefact file does not exist. */
    data object Missing : ArtefactCheck

    /** Keystore or I/O failed for a reason unrelated to the stored bytes; retry may succeed. */
    data object Transient : ArtefactCheck
}
