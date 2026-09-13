package org.openlife.vault.repository

sealed interface DeleteResult {
    data object Deleted : DeleteResult

    /** No such Source, or it is in a state (STAGED) this operation does not apply to. */
    data object NotFound : DeleteResult

    /** File cleanup failed; the row is left DELETING, durable for retry (design §12). */
    data object Failed : DeleteResult
}
