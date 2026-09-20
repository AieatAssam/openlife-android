package org.openlife.vault.repository

import java.io.ByteArrayOutputStream

/**
 * Synthetic EXIF writer used only by orientation tests. It inserts one bounded
 * APP1 segment after JPEG SOI and never uses user or downloaded media.
 */
object ExifFixtures {
    fun jpegWithOrientation(value: Int, littleEndian: Boolean = true): ByteArray {
        require(value in 1..8)
        val minimalJpeg = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xD9.toByte(),
        )
        return injectOrientation(minimalJpeg, value, littleEndian)
    }

    fun injectOrientation(jpeg: ByteArray, value: Int, littleEndian: Boolean = true): ByteArray {
        require(jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        require(value in 1..8)
        val tiff = ByteArrayOutputStream().apply {
            if (littleEndian) {
                write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
                write(byteArrayOf(0x01, 0x00))
                write(byteArrayOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00))
                write(byteArrayOf(value.toByte(), 0x00, 0x00, 0x00))
                write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            } else {
                write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
                write(byteArrayOf(0x00, 0x01))
                write(byteArrayOf(0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01))
                write(byteArrayOf(0x00, value.toByte(), 0x00, 0x00))
                write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            }
        }.toByteArray()
        val payload = byteArrayOf(
            'E'.code.toByte(),
            'x'.code.toByte(),
            'i'.code.toByte(),
            'f'.code.toByte(),
            0,
            0,
        ) + tiff
        val segmentLength = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(),
            0xE1.toByte(),
            (segmentLength shr 8).toByte(),
            segmentLength.toByte(),
        ) + payload
        return jpeg.copyOfRange(0, 2) +
            app1 +
            jpeg.copyOfRange(2, jpeg.size)
    }
}
