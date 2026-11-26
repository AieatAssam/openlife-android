package org.openlife.vault.repository

import java.io.File

/**
 * P1-08 (added by review, acting owner 2026-11-26): proof that the user
 * confirmed a vault reset. [VaultResetRepository.resetVault] requires one, so
 * no code path can wipe the vault without it. It has no public constructor:
 * the only ways to get one are [ofTypedWord] (the Settings controller, when
 * the exact word was typed) and [ofIncompleteReset] (finishing a reset the
 * user already confirmed, whose marker file is still on disk). A source scan
 * (ResetConfirmationBoundaryTest) confines each call to its one caller.
 */
class ResetConfirmation private constructor() {
    companion object {
        /** A confirmation only when [typed] is exactly [required] (case, spaces and all) and [required] is not blank. */
        fun ofTypedWord(typed: String, required: String): ResetConfirmation? = TODO("P1-08 review: $typed $required")

        /** A confirmation only while [resetMarker] exists: a reset the user confirmed earlier is still unfinished. */
        fun ofIncompleteReset(resetMarker: File): ResetConfirmation? = TODO("P1-08 review: $resetMarker")
    }
}
