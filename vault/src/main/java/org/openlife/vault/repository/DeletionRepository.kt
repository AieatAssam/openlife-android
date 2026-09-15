package org.openlife.vault.repository

import java.util.UUID
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity

/**
 * Design §12 "Deletion contract". Deleting a Source is app-level deletion —
 * the OpenLife-owned copy and its row (wrapped DEK included, since it is a
 * column on that same row) are removed; this is not a forensic-erasure or
 * crypto-erasure guarantee, and deleting the provider's original (if any)
 * is never part of this operation.
 *
 * Runs under the same [MutationQueue] as import/save/recovery. A caller
 * initiating deletion on a `READY` or `CORRUPT` Source, or retrying one
 * already `DELETING` (e.g. after a prior file-cleanup failure), both take
 * the same path here — deletion is idempotent-safe to retry. A `STAGED`
 * Source is not a valid target: an in-progress import is abandoned via
 * cancellation, not user-triggered deletion.
 */
class DeletionRepository(
    private val paths: VaultPaths,
    private val database: OpenLifeDatabase,
    private val mutationQueue: MutationQueue,
    private val fileOps: ArtefactFileOps = ArtefactFileOps.Default,
) {

    suspend fun deleteSource(sourceId: UUID): DeleteResult = mutationQueue.acquire {
        val entity = database.sourceDao().findById(sourceId.toString()) ?: return@acquire DeleteResult.NotFound
        val source = entity.toDomain()

        if (source.state != SourceState.READY &&
            source.state != SourceState.CORRUPT &&
            source.state != SourceState.DELETING
        ) {
            return@acquire DeleteResult.NotFound
        }

        // Committed before any file is touched, so a kill partway through
        // leaves an unambiguous, resumable state (design §12; also handled
        // by RecoveryRepository's DELETING branch on next startup).
        if (source.state != SourceState.DELETING) {
            database.sourceDao().update(source.copy(state = SourceState.DELETING).toEntity())
        }

        val stageRemoved = fileOps.deleteIfExists(paths.stageFile(sourceId))
        val blobRemoved = fileOps.deleteIfExists(paths.blobFile(sourceId))
        if (!stageRemoved || !blobRemoved) {
            return@acquire DeleteResult.Failed
        }

        database.sourceDao().deleteById(sourceId.toString())
        DeleteResult.Deleted
    }

}
