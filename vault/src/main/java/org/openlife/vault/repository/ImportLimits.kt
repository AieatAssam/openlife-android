package org.openlife.vault.repository

/**
 * Bounded-input defaults from design §12. These are business limits on
 * what OpenLife will import, independent of and tighter than
 * [org.openlife.vault.crypto.EnvelopeFormat.MAX_CIPHERTEXT_LENGTH], which
 * exists purely to stop an unbounded allocation from a corrupt envelope
 * length field.
 */
object ImportLimits {
    /** Reject on byte 16 MiB + 1, even if the provider's declared size is absent or false. */
    const val MAX_ORIGINAL_BYTES: Long = 16L * 1024 * 1024

    /** Validated with overflow-safe arithmetic before any decode is attempted. */
    const val MAX_ENCODED_PIXELS: Long = 40_000_000L

    const val MAX_LONGEST_EDGE_PIXELS: Int = 16384

    /** Sample decode only; never allocate a full-size bitmap just for preview. */
    const val MAX_PREVIEW_PIXELS: Long = 4_000_000L

    const val MAX_CONCURRENT_IMPORTS: Int = 1

    const val PROVIDER_READ_DEADLINE_SECONDS: Long = 15
}
