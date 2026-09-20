package org.openlife.vault.repository

import org.openlife.vault.model.ImageFormat

/** Non-sensitive rejection categories (design §9: no content-revealing error detail). */
enum class ImageRejectionReason {
    EXCEEDS_BYTE_LIMIT,
    UNSUPPORTED_FORMAT,
    DECLARED_FORMAT_MISMATCH,
    CORRUPT_CONTENT,
    ANIMATED_NOT_SUPPORTED,
    EXCEEDS_DIMENSION_LIMIT,
    STORAGE_UNAVAILABLE,
}

sealed interface ImageValidationResult {
    data class Valid(val format: ImageFormat, val width: Int, val height: Int) : ImageValidationResult
    data class Rejected(val reason: ImageRejectionReason) : ImageValidationResult
}
