package org.openlife.vault.repository

import org.openlife.vault.model.Orientation

/** Bounded JPEG APP1/Exif orientation reader; it never changes source bytes. */
object ExifOrientationParser {
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOI = 0xD8
    private const val JPEG_EOI = 0xD9
    private const val JPEG_SOS = 0xDA
    private const val JPEG_APP1 = 0xE1
    private const val JPEG_TEM = 0x01
    private const val JPEG_RST_FIRST = 0xD0
    private const val JPEG_RST_LAST = 0xD7
    private const val JPEG_MIN_BYTES = 2
    private const val MAX_MARKERS = 10_000
    private const val MAX_IFD_ENTRIES = 256
    private const val SEGMENT_LENGTH_BYTES = 2
    private const val EXIF_PREFIX_LENGTH = 6
    private const val TIFF_HEADER_LENGTH = 8
    private const val IFD_ENTRY_LENGTH = 12
    private const val IFD_COUNT_LENGTH = 2
    private const val ORIENTATION_TAG = 0x0112
    private const val SHORT_TYPE = 3
    private const val TIFF_MAGIC = 42
    private const val UINT16_SIZE = 2
    private const val UINT32_SIZE = 4
    private const val BYTE_MASK = 0xFFL
    private const val BYTE_SHIFT = 8
    private const val SHIFT_16 = 16
    private const val SHIFT_24 = 24
    private const val ORIENTATION_NORMAL = 1
    private const val ORIENTATION_FLIP_HORIZONTAL = 2
    private const val ORIENTATION_ROTATE_180 = 3
    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_TRANSVERSE = 7
    private const val ORIENTATION_ROTATE_270 = 8
    private const val EXIF_MAGIC = "Exif\u0000\u0000"

    private val exifMagicBytes = EXIF_MAGIC.toByteArray()

    fun parse(bytes: ByteArray): Orientation {
        if (!isJpeg(bytes)) return Orientation.NORMAL
        var offset = JPEG_MIN_BYTES
        repeat(MAX_MARKERS) {
            if (offset >= bytes.size) return Orientation.NORMAL
            val segment = readSegment(bytes, offset) ?: return Orientation.NORMAL
            when (segment.marker) {
                JPEG_EOI, JPEG_SOS -> return Orientation.NORMAL
                JPEG_APP1 -> parseExif(bytes, segment.payloadStart, segment.end)?.let { return it }
            }
            offset = segment.end
        }
        return Orientation.NORMAL
    }

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= JPEG_MIN_BYTES &&
        unsigned(bytes[0]) == JPEG_MARKER_PREFIX &&
        unsigned(bytes[1]) == JPEG_SOI

    private fun readSegment(bytes: ByteArray, offset: Int): JpegSegment? {
        if (offset >= bytes.size || unsigned(bytes[offset]) != JPEG_MARKER_PREFIX) return null
        var markerOffset = offset + 1
        while (markerOffset < bytes.size && unsigned(bytes[markerOffset]) == JPEG_MARKER_PREFIX) {
            markerOffset++
        }
        if (markerOffset >= bytes.size) return null
        val marker = unsigned(bytes[markerOffset])
        val lengthOffset = markerOffset + 1
        if (marker == JPEG_TEM || marker in JPEG_RST_FIRST..JPEG_RST_LAST) {
            return JpegSegment(marker, lengthOffset, lengthOffset)
        }
        val length = readUInt16(bytes, lengthOffset) ?: return null
        if (length < SEGMENT_LENGTH_BYTES) return null
        val segmentEnd = lengthOffset + length
        if (segmentEnd < lengthOffset || segmentEnd > bytes.size) return null
        return JpegSegment(marker, lengthOffset + SEGMENT_LENGTH_BYTES, segmentEnd)
    }

    private fun parseExif(bytes: ByteArray, start: Int, end: Int): Orientation? {
        val layout = readTiffLayout(bytes, start, end) ?: return null
        for (index in 0 until layout.entryCount) {
            val entry = layout.entriesStart + index * IFD_ENTRY_LENGTH
            if (readUInt16(bytes, entry, layout.littleEndian, end) == ORIENTATION_TAG) {
                return readOrientationEntry(bytes, entry, layout.littleEndian, end)
            }
        }
        return null
    }

    private fun readTiffLayout(bytes: ByteArray, start: Int, end: Int): TiffLayout? {
        if (end - start < EXIF_PREFIX_LENGTH + TIFF_HEADER_LENGTH) return null
        if (!bytes.copyOfRange(start, start + EXIF_PREFIX_LENGTH).contentEquals(exifMagicBytes)) return null
        val tiffStart = start + EXIF_PREFIX_LENGTH
        val littleEndian = when {
            bytes[tiffStart] == 'I'.code.toByte() && bytes[tiffStart + 1] == 'I'.code.toByte() -> true
            bytes[tiffStart] == 'M'.code.toByte() && bytes[tiffStart + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (readUInt16(bytes, tiffStart + 2, littleEndian, end) != TIFF_MAGIC) return null
        val ifdOffset = readUInt32(bytes, tiffStart + 4, littleEndian, end) ?: return null
        if (ifdOffset > (end - tiffStart).toLong()) return null
        val ifdStart = tiffStart + ifdOffset.toInt()
        val entryCount = readUInt16(bytes, ifdStart, littleEndian, end) ?: return null
        if (entryCount > MAX_IFD_ENTRIES) return null
        val entriesStart = ifdStart + IFD_COUNT_LENGTH
        val entriesEnd = entriesStart + entryCount * IFD_ENTRY_LENGTH
        if (entriesEnd < entriesStart || entriesEnd > end) return null
        return TiffLayout(littleEndian, entriesStart, entryCount)
    }

    private fun readOrientationEntry(bytes: ByteArray, entry: Int, littleEndian: Boolean, end: Int): Orientation {
        val type = readUInt16(bytes, entry + 2, littleEndian, end)
        val count = readUInt32(bytes, entry + 4, littleEndian, end)
        if (type != SHORT_TYPE || count != 1L) return Orientation.NORMAL
        val value = readUInt16(bytes, entry + 8, littleEndian, end) ?: return Orientation.NORMAL
        return orientationFor(value)
    }

    private fun orientationFor(value: Int): Orientation = when (value) {
        ORIENTATION_NORMAL -> Orientation.NORMAL
        ORIENTATION_FLIP_HORIZONTAL -> Orientation.FLIP_HORIZONTAL
        ORIENTATION_ROTATE_180 -> Orientation.ROTATE_180
        ORIENTATION_FLIP_VERTICAL -> Orientation.FLIP_VERTICAL
        ORIENTATION_TRANSPOSE -> Orientation.TRANSPOSE
        ORIENTATION_ROTATE_90 -> Orientation.ROTATE_90
        ORIENTATION_TRANSVERSE -> Orientation.TRANSVERSE
        ORIENTATION_ROTATE_270 -> Orientation.ROTATE_270
        else -> Orientation.NORMAL
    }

    private fun readUInt16(
        bytes: ByteArray,
        offset: Int,
        littleEndian: Boolean = false,
        limit: Int = bytes.size,
    ): Int? {
        if (offset < 0 || limit > bytes.size || offset > limit - UINT16_SIZE) return null
        val first = unsigned(bytes[offset])
        val second = unsigned(bytes[offset + 1])
        return if (littleEndian) first or (second shl BYTE_SHIFT) else (first shl BYTE_SHIFT) or second
    }

    private fun readUInt32(bytes: ByteArray, offset: Int, littleEndian: Boolean, limit: Int): Long? {
        if (offset < 0 || limit > bytes.size || offset > limit - UINT32_SIZE) return null
        val first = unsigned(bytes[offset]).toLong()
        val second = unsigned(bytes[offset + 1]).toLong()
        val third = unsigned(bytes[offset + 2]).toLong()
        val fourth = unsigned(bytes[offset + 3]).toLong()
        return if (littleEndian) {
            first or (second shl BYTE_SHIFT) or (third shl SHIFT_16) or (fourth shl SHIFT_24)
        } else {
            (first shl SHIFT_24) or (second shl SHIFT_16) or (third shl BYTE_SHIFT) or fourth
        }
    }

    private fun unsigned(value: Byte): Int = (value.toLong() and BYTE_MASK).toInt()

    private data class JpegSegment(val marker: Int, val payloadStart: Int, val end: Int)

    private data class TiffLayout(val littleEndian: Boolean, val entriesStart: Int, val entryCount: Int)
}
