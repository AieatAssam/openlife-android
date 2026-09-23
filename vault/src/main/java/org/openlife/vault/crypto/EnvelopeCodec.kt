package org.openlife.vault.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Encodes and decodes the fixed binary layout described in [EnvelopeFormat].
 * This is pure framing: it does not touch cryptographic keys and does not
 * verify authenticity. Decoding a tampered envelope can succeed here —
 * [AesGcmCodec.decrypt] is what fails closed on tampering. Callers must run
 * both steps; decoding alone never releases plaintext.
 */
object EnvelopeCodec {

    class MalformedEnvelopeException(message: String) : Exception(message)

    fun encode(envelope: Envelope): ByteArray {
        require(envelope.nonce.size == EnvelopeFormat.NONCE_LENGTH_BYTES) {
            "nonce must be ${EnvelopeFormat.NONCE_LENGTH_BYTES} bytes, was ${envelope.nonce.size}"
        }
        require(envelope.ciphertext.size <= EnvelopeFormat.MAX_CIPHERTEXT_LENGTH) {
            "ciphertext exceeds MAX_CIPHERTEXT_LENGTH (${EnvelopeFormat.MAX_CIPHERTEXT_LENGTH})"
        }

        val out = ByteArrayOutputStream(
            EnvelopeFormat.HEADER_LENGTH_BYTES +
                envelope.nonce.size +
                EnvelopeFormat.CIPHERTEXT_LENGTH_FIELD_BYTES +
                envelope.ciphertext.size,
        )
        out.write(EnvelopeFormat.MAGIC)
        out.write(EnvelopeFormat.VERSION.toInt())
        out.write(envelope.nonce.size)
        out.write(envelope.nonce)
        out.write(
            ByteBuffer.allocate(EnvelopeFormat.CIPHERTEXT_LENGTH_FIELD_BYTES)
                .putInt(envelope.ciphertext.size)
                .array(),
        )
        out.write(envelope.ciphertext)
        return out.toByteArray()
    }

    /**
     * Every length field is validated against the bytes actually present
     * before it is trusted, and the ciphertext length is bounds-checked
     * before its buffer is allocated (design §10). Any structural problem —
     * truncation, a bad magic, an unsupported version, an out-of-range or
     * mismatched length, trailing bytes — throws [MalformedEnvelopeException]
     * rather than guessing at a lenient interpretation.
     */
    fun decode(bytes: ByteArray): Envelope {
        if (bytes.size < EnvelopeFormat.HEADER_LENGTH_BYTES) {
            malformed("truncated header: only ${bytes.size} bytes")
        }
        val buffer = ByteBuffer.wrap(bytes)

        val magic = ByteArray(EnvelopeFormat.MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(EnvelopeFormat.MAGIC)) {
            malformed("bad magic")
        }

        val version = buffer.get()
        if (version != EnvelopeFormat.VERSION) {
            malformed("unsupported envelope version $version")
        }

        val nonceLength = buffer.get().toInt() and 0xFF
        if (nonceLength != EnvelopeFormat.NONCE_LENGTH_BYTES) {
            malformed("unexpected nonce length $nonceLength")
        }
        if (buffer.remaining() < nonceLength) {
            malformed("truncated nonce")
        }
        val nonce = ByteArray(nonceLength)
        buffer.get(nonce)

        if (buffer.remaining() < EnvelopeFormat.CIPHERTEXT_LENGTH_FIELD_BYTES) {
            malformed("truncated ciphertext length field")
        }
        val ciphertextLength = buffer.int
        if (ciphertextLength < 0 || ciphertextLength > EnvelopeFormat.MAX_CIPHERTEXT_LENGTH) {
            // Checked before any allocation below: a forged length field
            // cannot drive an unbounded allocation.
            malformed("ciphertext length out of bounds: $ciphertextLength")
        }
        if (buffer.remaining() != ciphertextLength) {
            malformed(
                "declared ciphertext length $ciphertextLength does not match " +
                    "remaining ${buffer.remaining()} bytes",
            )
        }
        val ciphertext = ByteArray(ciphertextLength)
        buffer.get(ciphertext)

        return Envelope(nonce, ciphertext)
    }

    private fun malformed(message: String): Nothing = throw MalformedEnvelopeException(message)
}
