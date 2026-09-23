package org.openlife.vault.repository

import java.util.UUID

sealed interface SaveResult {
    data class Saved(val sourceId: UUID) : SaveResult

    /** The new stage was discarded; [existingSourceId] is the pre-existing READY duplicate. */
    data class DuplicateFound(val existingSourceId: UUID) : SaveResult

    /** The referenced STAGED row/file is gone - already saved, cancelled, or cleaned up elsewhere. */
    data object StageNotFound : SaveResult

    /** A durable write could not complete because storage is full or unavailable. */
    data object StorageUnavailable : SaveResult

    /** Authentication, rename, or commit failed; the row is left STAGED for recovery to resolve. */
    data object Failed : SaveResult
}
