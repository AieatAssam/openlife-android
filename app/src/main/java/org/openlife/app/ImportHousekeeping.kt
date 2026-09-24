package org.openlife.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openlife.vault.repository.ImportSlot
import java.util.UUID

/**
 * Housekeeping for imports whose screen went away (P1-15, P1-01). Owned by
 * [OpenLifeApp]; [appScope] is application-scoped, so the work outlives the
 * ViewModel that asked for it. Nothing here logs content or identifiers.
 */
class ImportHousekeeping(private val vault: suspend () -> VaultAccess, private val currentSlot: () -> ImportSlot?) {
    /** Application scope for this cleanup only (P1-01 constraint). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Frees the one-import slot when an intake screen goes away after the
     * import was decided, so the next share is not refused as busy (P1-15).
     */
    fun releaseImportSlot(sourceId: UUID) {
        currentSlot()?.release(sourceId)
    }

    /**
     * Frees the slot at once (so an immediate re-share is not refused) and
     * cancels the STAGED import whose intake screen is gone in the background
     * (P1-01-R1).
     */
    fun cancelAbandonedImport(sourceId: UUID) {
        releaseImportSlot(sourceId)
        appScope.launch { (vault() as? VaultAccess.Ready)?.importRepository?.cancelStagedImport(sourceId) }
    }

    /**
     * Light recovery on foreground (P1-01-R2/R3): removes stages that no
     * screen owns. Returns null when a mutation is in progress; the next
     * foreground tries again.
     */
    suspend fun cleanAbandonedStages(): Int? {
        val access = vault() as? VaultAccess.Ready ?: return null
        return access.recoveryRepository.cleanAbandonedStages(access.importRepository.importSlot)
    }

    /** Test hook: waits until application-scoped cleanup started so far has finished. */
    suspend fun awaitIdleForTest() {
        appScope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }
}
