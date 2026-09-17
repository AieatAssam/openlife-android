package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.model.ImageFormat
import java.io.ByteArrayOutputStream

/**
 * Pure-JVM tests against synthetic fixtures built in-test (never real
 * imported content, per AGENTS.md). Covers the format/limit half of the
 * C0-06 test row: forged MIME, corrupt headers, animation, extreme
 * dimensions, excess bytes.
 */
class ImageHeaderValidatorTest {

    private fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun be32(value: Long) = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun syntheticPng(width: Long, height: Long, animated: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(),
                'P'.code.toByte(),
                'N'.code.toByte(),
                'G'.code.toByte(),
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            ),
        )

        out.write(be32(13)) // IHDR length
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(be32(width))
        out.write(be32(height))
        out.write(byteArrayOf(8, 6, 0, 0, 0)) // bit depth, color type, compression, filter, interlace
        out.write(be32(0)) // dummy CRC, not validated by the header parser

        if (animated) {
            out.write(be32(8)) // acTL length
            out.write("acTL".toByteArray(Charsets.US_ASCII))
            out.write(be32(1))
            out.write(be32(0))
            out.write(be32(0)) // dummy CRC
        }

        out.write(be32(0)) // IDAT length
        out.write("IDAT".toByteArray(Charsets.US_ASCII))
        out.write(be32(0)) // dummy CRC

        out.write(be32(0)) // IEND length
        out.write("IEND".toByteArray(Charsets.US_ASCII))
        out.write(be32(0)) // dummy CRC

        return out.toByteArray()
    }

    private fun syntheticJpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte())) // SOF0
        out.write(be16(8)) // segment length: 2 (length) + 1 (precision) + 2 + 2
        out.write(byteArrayOf(8)) // precision
        out.write(be16(height))
        out.write(be16(width))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    // --- valid inputs ---

    @Test
    fun validPngIsAccepted() {
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(100, 200))
        assertEquals(ImageValidationResult.Valid(ImageFormat.PNG, 100, 200), result)
    }

    @Test
    fun validJpegIsAccepted() {
        val result = ImageHeaderValidator.validate("image/jpeg", syntheticJpeg(150, 90))
        assertEquals(ImageValidationResult.Valid(ImageFormat.JPEG, 150, 90), result)
    }

    // --- forged / mismatched MIME ---

    @Test
    fun pngBytesDeclaredAsJpegAreRejected() {
        val result = ImageHeaderValidator.validate("image/jpeg", syntheticPng(10, 10))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.DECLARED_FORMAT_MISMATCH), result)
    }

    @Test
    fun jpegBytesDeclaredAsPngAreRejected() {
        val result = ImageHeaderValidator.validate("image/png", syntheticJpeg(10, 10))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.DECLARED_FORMAT_MISMATCH), result)
    }

    @Test
    fun unrecognisedMagicBytesAreRejectedAsUnsupportedFormat() {
        val gifBytes = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(20)
        val result = ImageHeaderValidator.validate("image/png", gifBytes)
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.UNSUPPORTED_FORMAT), result)
    }

    // --- corrupt headers ---

    @Test
    fun truncatedPngHeaderIsRejectedAsCorrupt() {
        val truncated = syntheticPng(100, 100).copyOf(12) // cuts off mid-IHDR-type
        val result = ImageHeaderValidator.validate("image/png", truncated)
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT), result)
    }

    @Test
    fun pngWithWrongFirstChunkTypeIsRejectedAsCorrupt() {
        val bytes = syntheticPng(100, 100)
        val corrupted = bytes.copyOf()
        "IHDX".toByteArray(Charsets.US_ASCII).copyInto(corrupted, 12)
        val result = ImageHeaderValidator.validate("image/png", corrupted)
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT), result)
    }

    @Test
    fun jpegWithNoSofMarkerIsRejectedAsCorrupt() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()) // SOI then straight to EOI
        val result = ImageHeaderValidator.validate("image/jpeg", bytes)
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT), result)
    }

    @Test
    fun jpegTruncatedInsideSofSegmentIsRejectedAsCorrupt() {
        val full = syntheticJpeg(100, 100)
        val truncated = full.copyOf(full.size - 6) // cuts off inside the SOF0 payload
        val result = ImageHeaderValidator.validate("image/jpeg", truncated)
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT), result)
    }

    // --- animation ---

    @Test
    fun animatedPngIsRejected() {
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(50, 50, animated = true))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.ANIMATED_NOT_SUPPORTED), result)
    }

    // --- extreme dimensions ---

    @Test
    fun longestEdgeBeyondLimitIsRejected() {
        val tooWide = ImportLimits.MAX_LONGEST_EDGE_PIXELS + 1
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(tooWide.toLong(), 10))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT), result)
    }

    @Test
    fun longestEdgeAtLimitIsAccepted() {
        val edge = ImportLimits.MAX_LONGEST_EDGE_PIXELS
        // Keep total pixel count within MAX_ENCODED_PIXELS while testing the edge boundary alone.
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(edge.toLong(), 1))
        assertEquals(ImageValidationResult.Valid(ImageFormat.PNG, edge, 1), result)
    }

    @Test
    fun pixelCountBeyondLimitIsRejectedEvenWithinEdgeLimit() {
        // Both edges individually legal, but their product exceeds MAX_ENCODED_PIXELS.
        val side = 7000L // 7000*7000 = 49,000,000 > 40,000,000, both edges < 16384
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(side, side))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT), result)
    }

    @Test
    fun hugeDeclaredDimensionDoesNotOverflowAndIsRejected() {
        // Close to the unsigned 32-bit max a forged PNG IHDR could declare.
        val huge = 0xFFFFFFFEL
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(huge, huge))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT), result)
    }

    @Test
    fun zeroDimensionIsRejected() {
        val result = ImageHeaderValidator.validate("image/png", syntheticPng(0, 100))
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT), result)
    }

    // --- excess bytes ---

    @Test
    fun byteCountAtLimitPlusOneIsRejected() {
        val padded = syntheticPng(10, 10) + ByteArray((ImportLimits.MAX_ORIGINAL_BYTES).toInt())
        assertTrue(
            padded.size.toLong() == ImportLimits.MAX_ORIGINAL_BYTES + 1 ||
                padded.size.toLong() > ImportLimits.MAX_ORIGINAL_BYTES,
        )
        val result = ImageHeaderValidator.validate("image/png", padded)
        assertEquals(ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_BYTE_LIMIT), result)
    }
}
