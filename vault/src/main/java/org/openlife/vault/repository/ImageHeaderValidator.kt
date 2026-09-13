package org.openlife.vault.repository

import org.openlife.vault.model.ImageFormat

/**
 * Validates a bounded byte array as a supported, non-animated JPEG or PNG
 * with dimensions inside the design §12 limits — all from the raw bytes,
 * without decoding a bitmap. This is deliberately pure Kotlin with no
 * Android dependency: it is the format/limit half of design §11 step 3
 * ("Validate format, header bounds and dimensions"), tested on the JVM
 * against synthetic fixtures per the C0-06 test row. The sampled decode
 * step that follows a [ImageValidationResult.Valid] result needs
 * `android.graphics.BitmapFactory` and lives in the repository's
 * Android-side code, not here.
 *
 * Every rejection path is checked before any allocation proportional to a
 * declared (attacker-controlled) size, and dimension arithmetic uses `Long`
 * throughout to stay overflow-safe even for a forged 32-bit PNG dimension
 * field claiming close to 4 billion pixels on a side.
 */
object ImageHeaderValidator {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A
    )
    private const val PNG_IHDR_OFFSET = 8
    private const val PNG_CHUNK_HEADER_SIZE = 8 // 4-byte length + 4-byte type
    private const val PNG_CRC_SIZE = 4
    private const val MAX_PNG_CHUNKS_SCANNED = 10_000

    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private const val MAX_JPEG_MARKERS_SCANNED = 10_000

    fun validate(declaredMimeType: String, bytes: ByteArray): ImageValidationResult {
        if (bytes.size.toLong() > ImportLimits.MAX_ORIGINAL_BYTES) {
            return ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_BYTE_LIMIT)
        }

        val actualFormat = sniffFormat(bytes)
            ?: return ImageValidationResult.Rejected(ImageRejectionReason.UNSUPPORTED_FORMAT)

        val declaredFormat = ImageFormat.entries.find { it.mimeType == declaredMimeType }
        if (declaredFormat != actualFormat) {
            return ImageValidationResult.Rejected(ImageRejectionReason.DECLARED_FORMAT_MISMATCH)
        }

        val dimensions = when (actualFormat) {
            ImageFormat.PNG -> parsePng(bytes)
            ImageFormat.JPEG -> parseJpeg(bytes)
        } ?: return ImageValidationResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT)

        if (actualFormat == ImageFormat.PNG && isAnimatedPng(bytes)) {
            return ImageValidationResult.Rejected(ImageRejectionReason.ANIMATED_NOT_SUPPORTED)
        }

        val (width, height) = dimensions
        val pixelCount = width * height // both already Long; cannot overflow before this multiply
        if (width <= 0 || height <= 0 ||
            width > ImportLimits.MAX_LONGEST_EDGE_PIXELS ||
            height > ImportLimits.MAX_LONGEST_EDGE_PIXELS ||
            pixelCount > ImportLimits.MAX_ENCODED_PIXELS
        ) {
            return ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT)
        }

        return ImageValidationResult.Valid(actualFormat, width.toInt(), height.toInt())
    }

    private fun sniffFormat(bytes: ByteArray): ImageFormat? = when {
        bytes.size >= PNG_SIGNATURE.size && bytes.startsWith(PNG_SIGNATURE) -> ImageFormat.PNG
        bytes.size >= JPEG_SOI.size && bytes.startsWith(JPEG_SOI) -> ImageFormat.JPEG
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun ByteArray.readUInt32BE(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) throw IndexOutOfBoundsException()
        return ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)
    }

    private fun ByteArray.readUInt16BE(offset: Int): Int {
        if (offset < 0 || offset + 2 > size) throw IndexOutOfBoundsException()
        return ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
    }

    /** Returns (width, height) as Long, or null if the header cannot be parsed. */
    private fun parsePng(bytes: ByteArray): Pair<Long, Long>? = try {
        val ihdrType = String(bytes, PNG_IHDR_OFFSET + 4, 4, Charsets.US_ASCII)
        if (ihdrType != "IHDR") {
            null
        } else {
            val width = bytes.readUInt32BE(PNG_IHDR_OFFSET + PNG_CHUNK_HEADER_SIZE)
            val height = bytes.readUInt32BE(PNG_IHDR_OFFSET + PNG_CHUNK_HEADER_SIZE + 4)
            width to height
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** Scans chunks after IHDR for an `acTL` chunk appearing before the first `IDAT` (APNG). */
    private fun isAnimatedPng(bytes: ByteArray): Boolean {
        // IHDR is always exactly a 13-byte payload per the PNG spec.
        var offset = PNG_IHDR_OFFSET + PNG_CHUNK_HEADER_SIZE + 13 + PNG_CRC_SIZE
        var chunksScanned = 0
        while (offset + PNG_CHUNK_HEADER_SIZE <= bytes.size && chunksScanned < MAX_PNG_CHUNKS_SCANNED) {
            chunksScanned++
            val dataLength = try {
                bytes.readUInt32BE(offset)
            } catch (e: IndexOutOfBoundsException) {
                return false
            }
            val type = try {
                String(bytes, offset + 4, 4, Charsets.US_ASCII)
            } catch (e: IndexOutOfBoundsException) {
                return false
            }
            when (type) {
                "acTL" -> return true
                "IDAT" -> return false
            }
            val next = offset + PNG_CHUNK_HEADER_SIZE + dataLength + PNG_CRC_SIZE
            if (next <= offset || next > bytes.size) return false // malformed length, stop scanning
            offset = next.toInt()
        }
        return false
    }

    /** Returns (width, height) as Long from the first SOF marker, or null if not found/malformed. */
    private fun parseJpeg(bytes: ByteArray): Pair<Long, Long>? {
        var offset = JPEG_SOI.size
        var markersScanned = 0
        while (offset + 1 < bytes.size && markersScanned < MAX_JPEG_MARKERS_SCANNED) {
            markersScanned++
            if (bytes[offset] != 0xFF.toByte()) return null // expected a marker
            var markerOffset = offset + 1
            // Skip fill bytes (0xFF padding is legal between markers).
            while (markerOffset < bytes.size && bytes[markerOffset] == 0xFF.toByte()) markerOffset++
            if (markerOffset >= bytes.size) return null
            val marker = bytes[markerOffset].toInt() and 0xFF
            val afterMarker = markerOffset + 1

            if (marker == 0xD9) return null // EOI reached with no SOF found
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                // Standalone markers carry no length field.
                offset = afterMarker
                continue
            }
            if (afterMarker + 2 > bytes.size) return null
            val segmentLength = bytes.readUInt16BE(afterMarker) // includes the 2 length bytes
            if (segmentLength < 2) return null

            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val dataStart = afterMarker + 2
                if (dataStart + 5 > bytes.size) return null
                val height = bytes.readUInt16BE(dataStart + 1).toLong()
                val width = bytes.readUInt16BE(dataStart + 3).toLong()
                return width to height
            }
            if (marker == 0xDA) return null // start of scan reached with no SOF found

            val next = afterMarker + segmentLength
            if (next <= offset || next > bytes.size) return null
            offset = next
        }
        return null
    }
}
