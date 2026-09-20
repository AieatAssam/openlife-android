package org.openlife.vault.repository

import java.io.ByteArrayOutputStream

/** Synthetic EXIF APP1 writer for instrumented preservation tests. */
object ExifFixtures {
    fun injectOrientation(jpeg: ByteArray, value: Int, littleEndian: Boolean = true): ByteArray {
        require(jpeg.size >= 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        val tiff = ByteArrayOutputStream().apply {
            if (littleEndian) {
                write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
                write(byteArrayOf(0x01, 0x00, 0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00))
                write(byteArrayOf(value.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            } else {
                write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08))
                write(byteArrayOf(0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01))
                write(byteArrayOf(0x00, value.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            }
        }.toByteArray()
        val payload = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0) + tiff
        val segmentLength = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (segmentLength shr 8).toByte(), segmentLength.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }
}
