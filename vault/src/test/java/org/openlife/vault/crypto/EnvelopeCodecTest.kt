package org.openlife.vault.crypto

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Framing-level tests for [EnvelopeCodec]: every length field must be
 * validated against the bytes actually present, and none of magic,
 * version, or length corruption may be silently tolerated. Authenticity
 * (tag/ciphertext/nonce tampering) is covered separately in
 * [AesGcmCodecTest] since that requires [AesGcmCodec], not just framing.
 */
class EnvelopeCodecTest {

    private val nonce = ByteArray(EnvelopeFormat.NONCE_LENGTH_BYTES) { it.toByte() }
    private val ciphertext = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun roundTripsAnEnvelope() {
        val envelope = Envelope(nonce, ciphertext)
        val decoded = EnvelopeCodec.decode(EnvelopeCodec.encode(envelope))
        assertArrayEquals(nonce, decoded.nonce)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun rejectsWrongNonceLengthOnEncode() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope(ByteArray(8), ciphertext))
        }
    }

    @Test
    fun rejectsOversizedCiphertextOnEncode() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCodec.encode(Envelope(nonce, ByteArray(EnvelopeFormat.MAX_CIPHERTEXT_LENGTH + 1)))
        }
    }

    @Test
    fun rejectsTruncatedHeader() {
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(byteArrayOf(0x4f, 0x4c))
        }
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        corrupted[0] = 'X'.code.toByte()
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        corrupted[EnvelopeFormat.MAGIC.size] = 99
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }

    @Test
    fun rejectsWrongDeclaredNonceLength() {
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        val nonceLengthIndex = EnvelopeFormat.MAGIC.size + 1
        corrupted[nonceLengthIndex] = (EnvelopeFormat.NONCE_LENGTH_BYTES - 1).toByte()
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }

    @Test
    fun rejectsNegativeCiphertextLength() {
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        val lengthFieldIndex = EnvelopeFormat.HEADER_LENGTH_BYTES + nonce.size
        val negativeOne = ByteBuffer.allocate(4).putInt(-1).array()
        negativeOne.copyInto(corrupted, lengthFieldIndex)
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }

    @Test
    fun rejectsCiphertextLengthBeyondMaximum() {
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        val lengthFieldIndex = EnvelopeFormat.HEADER_LENGTH_BYTES + nonce.size
        val tooLarge = ByteBuffer.allocate(4).putInt(EnvelopeFormat.MAX_CIPHERTEXT_LENGTH + 1).array()
        tooLarge.copyInto(corrupted, lengthFieldIndex)
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }

    @Test
    fun rejectsDeclaredLengthShorterThanActualCiphertext() {
        // Declares fewer bytes than are actually present: trailing bytes
        // after the declared length must not be silently accepted.
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        val lengthFieldIndex = EnvelopeFormat.HEADER_LENGTH_BYTES + nonce.size
        val shorter = ByteBuffer.allocate(4).putInt(ciphertext.size - 1).array()
        shorter.copyInto(corrupted, lengthFieldIndex)
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }

    @Test
    fun rejectsDeclaredLengthLongerThanActualCiphertext() {
        val bytes = EnvelopeCodec.encode(Envelope(nonce, ciphertext))
        val corrupted = bytes.copyOf()
        val lengthFieldIndex = EnvelopeFormat.HEADER_LENGTH_BYTES + nonce.size
        val longer = ByteBuffer.allocate(4).putInt(ciphertext.size + 1).array()
        longer.copyInto(corrupted, lengthFieldIndex)
        assertThrows(EnvelopeCodec.MalformedEnvelopeException::class.java) {
            EnvelopeCodec.decode(corrupted)
        }
    }
}
