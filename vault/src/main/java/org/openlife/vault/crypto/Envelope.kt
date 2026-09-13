package org.openlife.vault.crypto

/**
 * Raw envelope contents after framing: a fresh nonce and the GCM
 * ciphertext with its authentication tag appended. Never contains
 * plaintext, and never contains the AAD used to produce it — see
 * [EnvelopeFormat].
 */
data class Envelope(val nonce: ByteArray, val ciphertext: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Envelope) return false
        return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}
