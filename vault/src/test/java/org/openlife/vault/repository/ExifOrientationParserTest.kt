package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.vault.model.Orientation

class ExifOrientationParserTest {

    @Test
    fun readsEachOfTheEightOrientationsFromLittleAndBigEndianExif() {
        val expected = Orientation.entries.toList()
        for (value in 1..8) {
            assertEquals(expected[value - 1], ExifOrientationParser.parse(ExifFixtures.jpegWithOrientation(value, true)))
            assertEquals(expected[value - 1], ExifOrientationParser.parse(ExifFixtures.jpegWithOrientation(value, false)))
        }
    }

    @Test
    fun returnsNormalForMissingApp1TruncatedIfdAndOutOfRangeValues() {
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        val valid = ExifFixtures.jpegWithOrientation(6)
        assertEquals(Orientation.NORMAL, ExifOrientationParser.parse(valid.copyOf(10)))

        val outOfRange = ExifFixtures.jpegWithOrientation(6).copyOf()
        outOfRange[outOfRange.lastIndex - 7] = 0x09
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
