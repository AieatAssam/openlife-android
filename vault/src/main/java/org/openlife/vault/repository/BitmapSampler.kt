package org.openlife.vault.repository

/**
 * The sampled-decode step from design §11 step 3, kept behind an interface
 * so [ImportRepository] stays free of a direct `android.graphics` reference
 * and its format/limit logic ([ImageHeaderValidator], [BoundedStreamReader])
 * stays unit-testable on the JVM. There is no DI framework in this project
 * (AGENTS.md) - this is a single interface with one production
 * implementation (`AndroidBitmapSampler`), wired explicitly at the
 * composition root, not a general abstraction layer.
 *
 * A failed or unavailable sample must not be treated as a validation
 * failure on its own; the header validator is the source of truth for
 * accept/reject. This only needs to succeed enough to produce a bounded
 * preview.
 */
fun interface BitmapSampler {
    /** Returns true if a bounded preview could be sampled from [bytes]. */
    fun canSample(bytes: ByteArray): Boolean
}

/**
 * P1-18 review addition (P1-19): the outcome of the sampled decode that
 * proves the platform can render an import. An OutOfMemoryError there is the
 * existing [PrepareResult.Failed] (nothing is staged yet); a refused decode
 * stays CORRUPT_CONTENT.
 */
internal enum class SampleCheck {
    SAMPLEABLE,
    UNDECODABLE,
    OUT_OF_MEMORY,
    ;

    fun refusal(): PrepareResult? = when (this) {
        SAMPLEABLE -> null
        UNDECODABLE -> PrepareResult.Rejected(ImageRejectionReason.CORRUPT_CONTENT)
        OUT_OF_MEMORY -> PrepareResult.Failed
    }
}

internal fun checkSample(sampler: BitmapSampler, bytes: ByteArray): SampleCheck = try {
    if (sampler.canSample(bytes)) SampleCheck.SAMPLEABLE else SampleCheck.UNDECODABLE
} catch (_: OutOfMemoryError) {
    SampleCheck.OUT_OF_MEMORY
}
