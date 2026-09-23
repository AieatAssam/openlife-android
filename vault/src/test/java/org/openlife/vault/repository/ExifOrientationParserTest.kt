package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.vault.model.Orientation

class ExifOrientationParserTest {

    @Test
    fun readsEachOfTheEightOrientationsFromLittleAndBigEndianExif() {
        val expected = listOf(
            Orientation.NORMAL,
            Orientation.FLIP_HORIZONTAL,
            Orientation.ROTATE_180,
            Orientation.FLIP_VERTICAL,
            Orientation.TRANSPOSE,
            Orientation.ROTATE_90,
            Orientation.TRANSVERSE,
            Orientation.ROTATE_270,
        )
        for (value in 1..8) {
            assertEquals(
                expected[value - 1],
                ExifOrientationParser.parse(ExifFixtures.jpegWithOrientation(value, true)),
            )
            assertEquals(
                expected[value - 1],
                ExifOrientationParser.parse(ExifFixtures.jpegWithOrientation(value, false)),
            )
        }
    }

    @Test
    fun returnsNormalForMissingApp1TruncatedIfdAndOutOfRangeValues() {
        val missingApp1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(missingApp1))
        val valid = ExifFixtures.jpegWithOrientation(6)
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(valid.copyOf(10)))

        val afterSos = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xDA.toByte(),
            0x00,
            0x02,
        ) + valid.copyOfRange(2, valid.size)
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(afterSos))

        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(pngHeader))

        val outOfRange = ExifFixtures.jpegWithOrientation(6).copyOf()
        outOfRange[30] = 0x09
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(outOfRange))
    }

    @Test
    fun neverReadsPastTheBufferOnHostileOffsets() {
        val seed = ExifFixtures.jpegWithOrientation(6)
        repeat(10_000) { iteration ->
            val mutated = seed.copyOf()
            val index = 2 + (iteration * 7) % (mutated.size - 2)
            mutated[index] = (iteration * 31).toByte()
            try {
                ExifOrientationParser.parse(mutated)
            } catch (error: Throwable) {
                fail("mutation $iteration escaped the parser: ${error::class.simpleName}")
            }
        }
    }
}
