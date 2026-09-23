package org.openlife.vault.repository

import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.Orientation
import java.util.UUID

sealed interface PrepareResult {
    data class Prepared(
        val sourceId: UUID,
        val format: ImageFormat,
        val width: Int,
        val height: Int,
        val byteCount: Long,
        val orientation: Orientation = Orientation.NORMAL,
    ) : PrepareResult

    /** Another import is already in progress; the user must finish or cancel it first. */
    data object Busy : PrepareResult

    data class Rejected(val reason: ImageRejectionReason) : PrepareResult

    /** The device could not reserve enough durable space for this import. */
    data object StorageUnavailable : PrepareResult

    /** A provider read, encryption, or file-write failure unrelated to the content itself. */
    data object Failed : PrepareResult
}
