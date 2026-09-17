package org.openlife.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the exact wire bytes of a v1 envelope so an accidental change to the
 * binary layout (field order, byte order, length semantics) is caught even
 * if every unit test written against [EnvelopeCodec] and [AesGcmCodec]
 * directly still passes against itself. The fixture file was generated once
 * with a fixed key/nonce/plaintext/AAD (see the constants below) using the
 * JDK's own AES/GCM provider — the point is not to second-guess the JDK's
 * AES-GCM implementation, it is to freeze what OpenLife's framing produces
 * on disk. Design §10: "store an encoding fixture ... before integrating
 * UI."
 *
 * If this test ever needs to change, the fixture file must be regenerated
 * deliberately and the reason recorded in docs/decisions/ — a silent diff
 * here means the on-disk format changed, which breaks reading every
 * previously saved Source.
 */
class GoldenEnvelopeFixtureTest {

    private val key = SecretKeySpec(
        HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
        "AES",
    )
    private val plaintext = "OpenLife fixture plaintext for envelope tests".toByteArray(StandardCharsets.UTF_8)
    private val aad = EnvelopeAad.forDomain(EnvelopeDomain.ARTEFACT, version = 1)

    private fun readFixture(): ByteArray {
        val hex = requireNotNull(
            javaClass.getResourceAsStream("/envelope/golden-artefact-v1.hex"),
        ) { "golden fixture resource missing" }.bufferedReader().readText().trim()
        return HexFormat.of().parseHex(hex)
    }

    @Test
    fun goldenEnvelopeDecodesAndDecryptsToExpectedPlaintext() {
        val envelope = EnvelopeCodec.decode(readFixture())
        assertEquals(EnvelopeFormat.NONCE_LENGTH_BYTES, envelope.nonce.size)

        val decrypted = AesGcmCodec.decrypt(envelope, key, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun reEncodingTheDecodedGoldenEnvelopeReproducesTheSameBytes() {
        val original = readFixture()
        val envelope = EnvelopeCodec.decode(original)
        val reEncoded = EnvelopeCodec.encode(envelope)
        assertArrayEquals(original, reEncoded)
    }
}
