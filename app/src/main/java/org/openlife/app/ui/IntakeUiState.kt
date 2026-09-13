package org.openlife.app.ui

import java.util.UUID
import org.openlife.vault.model.ImageFormat

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
    data class Rejected(val message: String) : IntakeUiState

    data object Busy : IntakeUiState
    data object Failed : IntakeUiState
    data class VaultUnavailable(val reason: String) : IntakeUiState
    data object Cancelled : IntakeUiState
}
