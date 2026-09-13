package org.openlife.app

import org.openlife.vault.repository.DeletionRepository
import org.openlife.vault.repository.ImportRepository
import org.openlife.vault.repository.RecoveryReport
import org.openlife.vault.repository.SourceViewRepository

sealed interface VaultAccess {
    data class Ready(
        val importRepository: ImportRepository,
        val deletionRepository: DeletionRepository,
        val viewRepository: SourceViewRepository,
        val lastRecovery: RecoveryReport,
    ) : VaultAccess

    /** [reason] is the non-sensitive category from VaultBootstrapResult.Unavailable. */
    data class Unavailable(val reason: String) : VaultAccess
}
