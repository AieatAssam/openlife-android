package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-18 review addition (recorded in P1-19 notes): ImportRepository caught
 * OutOfMemoryError around the bounded read and the stage encryption, but not
 * around the sampled decode that proves the platform can render the image.
 * An OOM there is now the existing PrepareResult.Failed (nothing is staged
 * yet), not a crash; a refused decode stays CORRUPT_CONTENT.
 */
class SampleCheckTest {
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun outOfMemoryInTheSampledDecodeIsAFailedPrepare() {
        val exhausted = BitmapSampler { throw OutOfMemoryError("synthetic") }
        assertEquals(SampleCheck.OUT_OF_MEMORY, checkSample(exhausted, bytes))
        assertEquals(PrepareResult.Failed, SampleCheck.OUT_OF_MEMORY.refusal())
    }

    @Test
    fun aRefusedDecodeIsCorruptAndASampleableImageContinues() {
        assertEquals(SampleCheck.UNDECODABLE, checkSample(BitmapSampler { false }, bytes))
        assertEquals(
            PrepareResult.Rejected(org.openlife.vault.repository.ImageRejectionReason.CORRUPT_CONTENT),
            SampleCheck.UNDECODABLE.refusal(),
        )
        assertEquals(SampleCheck.SAMPLEABLE, checkSample(BitmapSampler { true }, bytes))
        assertEquals(null, SampleCheck.SAMPLEABLE.refusal())
    }
}
