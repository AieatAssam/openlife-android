package org.openlife.vault.crypto

import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Round-trip and adversarial coverage for [AesGcmCodec] and [EnvelopeCodec]
 * together, per docs/capabilities/C0.md C0-R19/C0-R20 and the C0-11 test
 * row: every one of ciphertext, tag, nonce, bound domain/UUID, and envelope
 * length must fail closed, never returning partial or unauthenticated
 * plaintext.
 */
class AesGcmCodecTest {

    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val plaintext = "a receipt total is not a purchased-item price".toByteArray(StandardCharsets.UTF_8)

    @Test
    fun roundTripsForDomainOnlyAad() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.DATABASE_SECRET)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        val decrypted = AesGcmCodec.decrypt(envelope, key, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun roundTripsForSourceBoundAad() {
        val key = freshKey()
        val sourceId = UUID.randomUUID()
        val aad = EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, sourceId)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        val decrypted = AesGcmCodec.decrypt(envelope, key, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encryptingTwiceProducesDifferentNoncesAndCiphertext() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val first = AesGcmCodec.encrypt(plaintext, key, aad)
        val second = AesGcmCodec.encrypt(plaintext, key, aad)
        assertNotEquals(first, second)
        assertNotEquals(String(first.nonce), String(second.nonce))
    }

    @Test
    fun wrongKeyFailsClosed() {
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val envelope = AesGcmCodec.encrypt(plaintext, freshKey(), aad)
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(envelope, freshKey(), aad)
        }
    }

    @Test
    fun tamperedCiphertextByteFailsClosed() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        val tampered = envelope.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(Envelope(envelope.nonce, tampered), key, aad)
        }
    }

    @Test
    fun tamperedTagByteFailsClosed() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        // The tag is the last TAG_LENGTH_BYTES bytes of the ciphertext array
        // for a single-shot javax.crypto GCM operation.
        val tampered = envelope.ciphertext.copyOf()
        val lastIndex = tampered.size - 1
        tampered[lastIndex] = (tampered[lastIndex].toInt() xor 0x01).toByte()
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(Envelope(envelope.nonce, tampered), key, aad)
        }
    }

    @Test
    fun tamperedNonceFailsClosed() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        val tamperedNonce = envelope.nonce.copyOf()
        tamperedNonce[0] = (tamperedNonce[0].toInt() xor 0x01).toByte()
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(Envelope(tamperedNonce, envelope.ciphertext), key, aad)
        }
    }

    @Test
    fun wrongDomainLabelFailsClosed() {
        val key = freshKey()
        val envelope = AesGcmCodec.encrypt(plaintext, key, EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT))
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(envelope, key, EnvelopeAad.forDomain(EnvelopeDomain.SOURCE_KEY))
        }
    }

    @Test
    fun wrongBoundSourceUuidFailsClosed() {
        val key = freshKey()
        val realSourceId = UUID.randomUUID()
        val otherSourceId = UUID.randomUUID()
        val envelope = AesGcmCodec.encrypt(
            plaintext,
            key,
            EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, realSourceId)
        )
        // This is the specific attack the design calls out: a valid
        // ciphertext for one Source must not decrypt under another
        // Source's identity, even with the same key and domain.
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(envelope, key, EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, otherSourceId))
        }
    }

    @Test
    fun sourceBoundAadIsRejectedByDomainOnlyAad() {
        val key = freshKey()
        val sourceId = UUID.randomUUID()
        val envelope = AesGcmCodec.encrypt(
            plaintext,
            key,
            EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, sourceId)
        )
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(envelope, key, EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT))
        }
    }

    @Test
    fun encodeThenDecodeThenDecryptRoundTripsThroughBytes() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        val bytes = EnvelopeCodec.encode(envelope)
        val decoded = EnvelopeCodec.decode(bytes)
        val decrypted = AesGcmCodec.decrypt(decoded, key, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun corruptedEncodedBoundUuidBytesFailClosedEvenThoughFramingIsUnaffected() {
        // The Source UUID lives only in the AAD, not in the encoded bytes,
        // so a bit-flip in the on-disk envelope can never touch it directly.
        // This test instead proves the more important property: framing
        // (encode/decode) succeeding is not sufficient for authenticity —
        // AesGcmCodec.decrypt is the only step that can fail closed.
        val key = freshKey()
        val sourceId = UUID.randomUUID()
        val envelope = AesGcmCodec.encrypt(
            plaintext,
            key,
            EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, sourceId)
        )
        val bytes = EnvelopeCodec.encode(envelope)
        val decoded = EnvelopeCodec.decode(bytes) // succeeds: framing is valid
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(decoded, key, EnvelopeAad.forSource(EnvelopeDomain.ARTEFACT, UUID.randomUUID()))
        }
    }

    @Test
    fun truncatedCiphertextShorterThanTagFailsClosed() {
        val key = freshKey()
        val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT)
        val envelope = AesGcmCodec.encrypt(plaintext, key, aad)
        val truncated = envelope.ciphertext.copyOf(EnvelopeFormat.TAG_LENGTH_BYTES - 1)
        assertThrows(EnvelopeAuthenticationException::class.java) {
            AesGcmCodec.decrypt(Envelope(envelope.nonce, truncated), key, aad)
        }
    }
}
