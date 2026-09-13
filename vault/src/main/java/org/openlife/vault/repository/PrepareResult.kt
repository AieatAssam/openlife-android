package org.openlife.vault.repository

import java.util.UUID
import org.openlife.vault.model.ImageFormat

sealed interface PrepareResult {
    data class Prepared(
        val sourceId: UUID,
        val format: ImageFormat,
        val width: Int,
        val height: Int,
        val byteCount: Long,
    ) : PrepareResult

    /** Another import is already in progress; the user must finish or cancel it first. */
    data object Busy : PrepareResult

    data class Rejected(val reason: ImageRejectionReason) : PrepareResult

    /** A provider read, encryption, or file-write failure unrelated to the content itself. */
    data object Failed : PrepareResult
}
