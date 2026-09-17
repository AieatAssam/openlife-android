package org.openlife.vault.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM/NoPadding encryption and decryption producing/consuming raw
 * [Envelope] content (nonce + ciphertext-with-tag). Nonces here are always
 * fresh and random — this file has no path that accepts a caller-chosen
 * nonce, because nonce reuse under the same key breaks GCM's
 * confidentiality and authenticity guarantees outright (design §10: "Never
 * reuse a DEK/nonce pair").
 *
 * This is deliberately separate from Keystore-backed encryption
 * (`KeystoreWrapper`, Stage 2), which must use a provider-generated IV
 * instead of `SecureRandom` — see design §10 / R5. Do not reuse this object
 * for Keystore-key operations.
 */
object AesGcmCodec {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val secureRandom = SecureRandom()

    fun encrypt(plaintext: ByteArray, key: SecretKey, aad: ByteArray): Envelope {
        val nonce = ByteArray(EnvelopeFormat.NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(EnvelopeFormat.TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return Envelope(nonce, ciphertext)
    }

    /**
     * Authenticates and decrypts. Any failure at all — wrong key, wrong AAD
     * (wrong domain or wrong bound Source UUID), a tampered ciphertext, a
     * tampered tag, a tampered nonce, or a structurally invalid ciphertext
     * length — surfaces as [EnvelopeAuthenticationException]. No partial or
     * unauthenticated plaintext is ever returned; every failure mode here
     * fails closed the same way (design §10).
     */
    fun decrypt(envelope: Envelope, key: SecretKey, aad: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(EnvelopeFormat.TAG_LENGTH_BITS, envelope.nonce),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(envelope.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw EnvelopeAuthenticationException(e)
        }
    }
}
