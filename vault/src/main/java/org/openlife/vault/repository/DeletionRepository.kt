package org.openlife.vault.repository

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity
import java.util.UUID

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sourceDao: SourceDao = database.sourceDao(),
) {

    suspend fun deleteSource(sourceId: UUID): DeleteResult = withContext(ioDispatcher) {
        mutationQueue.withMutation {
            try {
                val entity = sourceDao.findById(sourceId.toString())
                    ?: return@withMutation DeleteResult.NotFound
                val source = entity.toDomain()

                if (source.state != SourceState.READY &&
                    source.state != SourceState.CORRUPT &&
                    source.state != SourceState.DELETING
                ) {
                    return@withMutation DeleteResult.NotFound
                }

                // Committed before any file is touched, so a kill partway through
                // leaves an unambiguous, resumable state (design §12; also handled
                // by RecoveryRepository's DELETING branch on next startup).
                if (source.state != SourceState.DELETING) {
                    sourceDao.update(source.copy(state = SourceState.DELETING).toEntity())
                }

                val stageRemoved = fileOps.deleteIfExists(paths.stageFile(sourceId))
                val blobRemoved = fileOps.deleteIfExists(paths.blobFile(sourceId))
                if (!stageRemoved || !blobRemoved) {
                    return@withMutation DeleteResult.Failed
                }

                // Delete derived OCR rows on the same serialized mutation path as the
                // Source. The foreign keys also cascade in SQLite, but keeping this
                // explicit makes the C1 deletion contract independent of connection
                // pragma defaults and leaves no dependent provenance row behind.
                // One transaction: a failure leaves both tables as they were, with
                // the Source still DELETING for retry (P1-15-R3).
                database.withTransaction {
                    database.ocrDao().deleteForSource(sourceId.toString())
                    sourceDao.deleteById(sourceId.toString())
                }
                DeleteResult.Deleted
            } catch (error: java.io.IOException) {
                deleteFailureFor(error)
            } catch (error: ErrnoException) {
                deleteFailureFor(error)
            } catch (error: SQLiteFullException) {
                deleteFailureFor(error)
            } catch (_: Exception) {
                DeleteResult.Failed
            }
        }
    }

    private fun deleteFailureFor(error: Throwable): DeleteResult =
        if (IoFailureClassifier.classify(error) == IoFailureClassifier.Kind.STORAGE_UNAVAILABLE) {
            DeleteResult.StorageUnavailable
        } else {
            DeleteResult.Failed
        }
}
