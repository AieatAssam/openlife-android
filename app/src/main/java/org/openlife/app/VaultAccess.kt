package org.openlife.app

import org.openlife.vault.repository.DeletionRepository
import org.openlife.vault.repository.ImportRepository
import org.openlife.vault.repository.OcrRepository
import org.openlife.vault.repository.RecoveryReport
import org.openlife.vault.repository.SourceViewRepository
import org.openlife.vault.storage.VaultUnavailableCause

sealed interface VaultAccess {
    data class Ready(
        val importRepository: ImportRepository,
        val deletionRepository: DeletionRepository,
        val viewRepository: SourceViewRepository,
        val ocrRepository: OcrRepository,
        val lastRecovery: RecoveryReport,
    ) : VaultAccess

    data class Unavailable(val cause: VaultUnavailableCause) : VaultAccess
}
