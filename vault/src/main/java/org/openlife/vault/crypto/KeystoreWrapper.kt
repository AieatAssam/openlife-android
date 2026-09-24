package org.openlife.vault.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps and unwraps secrets under a single non-exportable AES-256-GCM key
 * held in Android Keystore for this installation (design §10, R4). This
 * key protects the database secret and every Source's per-file DEK; it
 * never leaves Keystore and this class never reads its raw bytes.
 *
 * Unlike [AesGcmCodec], which generates its own random nonce, Keystore
 * requires the provider to generate the IV for encryption (R5): calling
 * [Cipher.init] with [Cipher.ENCRYPT_MODE] and no parameter spec, then
 * reading it back via [Cipher.getIV] afterwards. Supplying a caller-chosen
 * IV to a Keystore key, or disabling randomized-encryption requirements, is
 * not done anywhere in this class.
 */
open class KeystoreWrapper(private val alias: String = DEFAULT_ALIAS) {

    companion object {
        const val DEFAULT_ALIAS = "openlife.vault.wrap.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * Returns the existing wrapping key, or creates it. Only a fresh vault
     * bootstrap may call this (P1-14-R2): every other path uses
     * [existingWrappingKey], so a key lost after install is reported, never
     * silently replaced (design §10, C0-R18).
     */
    fun ensureWrappingKey(): SecretKey {
        existingWrappingKey()?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Explicit default: Keystore must generate a fresh IV per
            // encryption. This is the setting R5 warns not to disable.
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** The wrapping key if it exists; never creates one. */
    open fun existingWrappingKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun requireWrappingKey(): SecretKey = existingWrappingKey() ?: throw MissingWrappingKeyException()

    /** True if a wrapping key already exists for this alias, without creating one. */
    fun hasWrappingKey(): Boolean = keyStore.containsAlias(alias)

    /** Removes this installation's wrapping key as part of an explicit vault reset. */
    fun deleteWrappingKey() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    /** @throws MissingWrappingKeyException when the wrapping key no longer exists; none is created. */
    open fun wrap(plaintext: ByteArray, domain: EnvelopeDomain, sourceId: UUID? = null): Envelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, requireWrappingKey())
        val iv = cipher.iv
        val aad = aadFor(domain, sourceId)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return Envelope(iv, ciphertext)
    }

    /**
     * @throws EnvelopeAuthenticationException when the envelope itself fails:
     *   a wrong tag, nonce or ciphertext (tampering or corruption).
     * @throws MissingWrappingKeyException when the wrapping key no longer
     *   exists. No replacement is created; the envelope is not evidence of
     *   corruption either.
     * @throws KeystoreUnavailableException when Keystore cannot perform the
     *   operation (daemon failure, locked or invalidated key). That is never
     *   evidence about the stored envelope, so callers must not treat it as
     *   corruption (P1-13-R5).
     */
    open fun unwrap(envelope: Envelope, domain: EnvelopeDomain, sourceId: UUID? = null): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                requireWrappingKey(),
                GCMParameterSpec(EnvelopeFormat.TAG_LENGTH_BITS, envelope.nonce),
            )
            cipher.updateAAD(aadFor(domain, sourceId))
            return cipher.doFinal(envelope.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw unwrapFailure(e)
        } catch (e: ProviderException) {
            throw KeystoreUnavailableException(e)
        }
    }

    /** Only a failure of the envelope itself (tag, padding, block size or IV) is authentication. */
    private fun unwrapFailure(e: GeneralSecurityException): Exception = when (e) {
        // BadPaddingException includes AEADBadTagException: the GCM tag did not verify.
        is BadPaddingException, is IllegalBlockSizeException, is InvalidAlgorithmParameterException ->
            EnvelopeAuthenticationException(e)

        else -> KeystoreUnavailableException(e)
    }

    private fun aadFor(domain: EnvelopeDomain, sourceId: UUID?): ByteArray =
        if (sourceId != null) EnvelopeAad.forSource(domain, sourceId) else EnvelopeAad.forDomain(domain)
}
