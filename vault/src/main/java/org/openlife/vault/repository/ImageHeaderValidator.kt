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
        0x89.toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )
    private const val PNG_IHDR_OFFSET = 8
    private const val PNG_CHUNK_HEADER_SIZE = 8 // 4-byte length + 4-byte type
    private const val PNG_IHDR_DATA_LENGTH = 13
    private const val PNG_CRC_SIZE = 4
    private const val MAX_PNG_CHUNKS_SCANNED = 10_000

    private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private const val MAX_JPEG_MARKERS_SCANNED = 10_000
    private const val BYTE_MASK = 0xFF
    private const val BYTE_MASK_LONG = 0xFFL
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_LONG_BYTE = 24
    private const val BITS_PER_SHORT_BYTE = 16
    private const val SHORT_BYTE_SHIFT = 8
    private const val UINT32_BYTE_COUNT = 4
    private const val UINT16_BYTE_COUNT = 2
    private const val LAST_UINT32_BYTE_OFFSET = 3
    private const val PNG_CHUNK_TYPE_OFFSET = 4
    private const val PNG_CHUNK_TYPE_LENGTH = 4
    private const val PNG_HEIGHT_OFFSET = 4
    private const val JPEG_MARKER_LENGTH_OFFSET = 2
    private const val JPEG_DIMENSION_START_OFFSET = 2
    private const val JPEG_DIMENSION_MIN_BYTES = 5
    private const val JPEG_PRECISION_OFFSET = 1
    private const val JPEG_WIDTH_OFFSET = 3
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_EOI_MARKER = 0xD9
    private const val JPEG_TEM_MARKER = 0x01
    private const val JPEG_RST_FIRST = 0xD0
    private const val JPEG_RST_LAST = 0xD7
    private const val JPEG_SOS_MARKER = 0xDA
    private const val JPEG_SOF_FIRST = 0xC0
    private const val JPEG_SOF_LAST = 0xCF
    private const val JPEG_DHT_MARKER = 0xC4
    private const val JPEG_JPG_MARKER = 0xC8
    private const val JPEG_DAC_MARKER = 0xCC

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

        if (actualFormat == ImageFormat.PNG) {
            if (isAnimatedPng(bytes)) {
                return ImageValidationResult.Rejected(ImageRejectionReason.ANIMATED_NOT_SUPPORTED)
            }
        }

        val (width, height) = dimensions
        val pixelCount = width * height // both already Long; cannot overflow before this multiply
        if (exceedsDimensionLimits(width, height, pixelCount)) {
            return ImageValidationResult.Rejected(ImageRejectionReason.EXCEEDS_DIMENSION_LIMIT)
        }

        return ImageValidationResult.Valid(actualFormat, width.toInt(), height.toInt())
    }

    private fun exceedsDimensionLimits(width: Long, height: Long, pixelCount: Long): Boolean {
        val invalidEdge = listOf(width, height).any(::exceedsEdgeLimit)
        return invalidEdge || pixelCount > ImportLimits.MAX_ENCODED_PIXELS
    }

    private fun exceedsEdgeLimit(edge: Long): Boolean = edge <= 0 || edge > ImportLimits.MAX_LONGEST_EDGE_PIXELS

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
        if (offset < 0 || offset + UINT32_BYTE_COUNT > size) {
            throw IndexOutOfBoundsException("uint32 offset out of bounds")
        }
        return ((this[offset].toLong() and BYTE_MASK_LONG) shl BITS_PER_LONG_BYTE) or
            ((this[offset + 1].toLong() and BYTE_MASK_LONG) shl BITS_PER_SHORT_BYTE) or
            ((this[offset + 2].toLong() and BYTE_MASK_LONG) shl BITS_PER_BYTE) or
            (this[offset + LAST_UINT32_BYTE_OFFSET].toLong() and BYTE_MASK_LONG)
    }

    private fun ByteArray.readUInt16BE(offset: Int): Int {
        if (offset < 0 || offset + UINT16_BYTE_COUNT > size) {
            throw IndexOutOfBoundsException("uint16 offset out of bounds")
        }
        return ((this[offset].toInt() and BYTE_MASK) shl SHORT_BYTE_SHIFT) or
            (this[offset + 1].toInt() and BYTE_MASK)
    }

    /** Returns (width, height) as Long, or null if the header cannot be parsed. */
    private fun parsePng(bytes: ByteArray): Pair<Long, Long>? = try {
        val ihdrType = String(
            bytes,
            PNG_IHDR_OFFSET + PNG_CHUNK_TYPE_OFFSET,
            PNG_CHUNK_TYPE_LENGTH,
            Charsets.US_ASCII,
        )
        if (ihdrType != "IHDR") {
            null
        } else {
            val width = bytes.readUInt32BE(PNG_IHDR_OFFSET + PNG_CHUNK_HEADER_SIZE)
            val height = bytes.readUInt32BE(PNG_IHDR_OFFSET + PNG_CHUNK_HEADER_SIZE + PNG_HEIGHT_OFFSET)
            width to height
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    /** Scans chunks after IHDR for an `acTL` chunk appearing before the first `IDAT` (APNG). */
    private fun isAnimatedPng(bytes: ByteArray): Boolean {
        // IHDR is always exactly a 13-byte payload per the PNG spec.
        var offset = PNG_IHDR_OFFSET + PNG_CHUNK_HEADER_SIZE + PNG_IHDR_DATA_LENGTH + PNG_CRC_SIZE
        var chunksScanned = 0
        while (offset + PNG_CHUNK_HEADER_SIZE <= bytes.size && chunksScanned < MAX_PNG_CHUNKS_SCANNED) {
            chunksScanned++
            val dataLength = try {
                bytes.readUInt32BE(offset)
            } catch (_: IndexOutOfBoundsException) {
                return false
            }
            val type = try {
                String(bytes, offset + PNG_CHUNK_TYPE_OFFSET, PNG_CHUNK_TYPE_LENGTH, Charsets.US_ASCII)
            } catch (_: IndexOutOfBoundsException) {
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
        var scan: JpegScan = JpegScan.Continue(offset)
        while (scan is JpegScan.Continue &&
            offset + 1 < bytes.size &&
            markersScanned < MAX_JPEG_MARKERS_SCANNED
        ) {
            markersScanned++
            scan = scanJpegMarker(bytes, offset)
            if (scan is JpegScan.Continue) offset = scan.offset
        }
        return (scan as? JpegScan.Dimensions)?.value
    }

    private fun scanJpegMarker(bytes: ByteArray, offset: Int): JpegScan {
        if (bytes[offset] != JPEG_MARKER_PREFIX.toByte()) return JpegScan.Stop
        val markerOffset = skipJpegFillBytes(bytes, offset + 1)
        if (markerOffset >= bytes.size) return JpegScan.Stop
        val marker = bytes[markerOffset].toInt() and BYTE_MASK
        val afterMarker = markerOffset + 1
        return when {
            marker == JPEG_EOI_MARKER -> JpegScan.Stop

            marker == JPEG_TEM_MARKER || marker in JPEG_RST_FIRST..JPEG_RST_LAST -> {
                JpegScan.Continue(afterMarker)
            }

            else -> scanJpegSegment(bytes, offset, afterMarker, marker)
        }
    }

    private fun skipJpegFillBytes(bytes: ByteArray, start: Int): Int {
        var markerOffset = start
        while (markerOffset < bytes.size && bytes[markerOffset] == JPEG_MARKER_PREFIX.toByte()) {
            markerOffset++
        }
        return markerOffset
    }

    private fun scanJpegSegment(bytes: ByteArray, offset: Int, afterMarker: Int, marker: Int): JpegScan {
        if (afterMarker + JPEG_MARKER_LENGTH_OFFSET > bytes.size) return JpegScan.Stop
        val segmentLength = bytes.readUInt16BE(afterMarker)
        if (segmentLength < JPEG_MARKER_LENGTH_OFFSET) return JpegScan.Stop
        val isSof = marker in JPEG_SOF_FIRST..JPEG_SOF_LAST &&
            marker != JPEG_DHT_MARKER && marker != JPEG_JPG_MARKER && marker != JPEG_DAC_MARKER
        if (isSof) {
            return jpegDimensions(bytes, afterMarker)
        }
        if (marker == JPEG_SOS_MARKER) return JpegScan.Stop
        val next = afterMarker + segmentLength
        return if (next <= offset || next > bytes.size) JpegScan.Stop else JpegScan.Continue(next)
    }

    private fun jpegDimensions(bytes: ByteArray, afterMarker: Int): JpegScan {
        val dataStart = afterMarker + JPEG_DIMENSION_START_OFFSET
        if (dataStart + JPEG_DIMENSION_MIN_BYTES > bytes.size) return JpegScan.Stop
        val height = bytes.readUInt16BE(dataStart + JPEG_PRECISION_OFFSET).toLong()
        val width = bytes.readUInt16BE(dataStart + JPEG_WIDTH_OFFSET).toLong()
        return JpegScan.Dimensions(width to height)
    }

    private sealed interface JpegScan {
        data object Stop : JpegScan
        data class Continue(val offset: Int) : JpegScan
        data class Dimensions(val value: Pair<Long, Long>) : JpegScan
    }
}
