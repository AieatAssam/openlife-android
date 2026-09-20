package org.openlife.app.ui

import androidx.annotation.StringRes
import org.openlife.app.R
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import java.util.UUID

enum class IntakeRejectionMessage(
    @StringRes val stringRes: Int,
) {
    FILE_TOO_LARGE(R.string.rejection_file_too_large),
    UNSUPPORTED_FORMAT(R.string.rejection_unsupported_format),
    DECLARED_FORMAT_MISMATCH(R.string.rejection_declared_format_mismatch),
    CORRUPT_CONTENT(R.string.rejection_corrupt_content),
    ANIMATED_NOT_SUPPORTED(R.string.rejection_animated_not_supported),
    IMAGE_TOO_LARGE(R.string.rejection_image_too_large),
    UNSUPPORTED_ACTION(R.string.rejection_unsupported_action),
    NO_IMAGE(R.string.rejection_no_image),
    MULTIPLE_ITEMS(R.string.rejection_multiple_items),
    UNSUPPORTED_SOURCE(R.string.rejection_unsupported_source),
    INVALID_SOURCE(R.string.rejection_invalid_source),
    MISSING_READ_ACCESS(R.string.rejection_missing_read_access),
    UNSUPPORTED_OR_MISSING_TYPE(R.string.rejection_unsupported_or_missing_type),
    ACCESS_RETRY(R.string.rejection_access_retry),
    TYPE_MISMATCH(R.string.rejection_type_mismatch),
}

sealed interface IntakeUiState {
    data object Preparing : IntakeUiState

    data class Preview(
        val sourceId: UUID,
        val format: ImageFormat,
        val width: Int,
        val height: Int,
        val byteCount: Long,
        /** Authenticated stage bytes for the preview image; null if sampling isn't available. */
        val previewBytes: ByteArray?,
        val orientation: Orientation = Orientation.NORMAL,
    ) : IntakeUiState

    data class Saving(val sourceId: UUID) : IntakeUiState
    data class Saved(val sourceId: UUID) : IntakeUiState
    data class Duplicate(val existingSourceId: UUID) : IntakeUiState

    /**
     * [message] is a plain, already-non-sensitive description. The
     * ViewModel maps `ImageRejectionReason` (from a failed Prepare) and
     * `IntakeActivity` maps `IntakeRejectionReason` (from intent-shape
     * validation) into this one shared shape, so the ViewModel does not
     * need to depend on the intent-validation types and vice versa.
     */
    data class Rejected(val message: IntakeRejectionMessage) : IntakeUiState

    data object Busy : IntakeUiState
    data object Failed : IntakeUiState
    data class VaultUnavailable(val reason: String) : IntakeUiState
    data object Cancelled : IntakeUiState
}
