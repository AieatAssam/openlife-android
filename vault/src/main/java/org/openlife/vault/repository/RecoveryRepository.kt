package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.EnvelopeCodec
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.StateTransition
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.compareAndSetState
import org.openlife.vault.storage.toDomain
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/**
 * Startup recovery per design §11's table. READY rows get only a cheap
 * existence-and-framing check here; deep authentication is on first read
 * and in [verifyAll] (P1-14-R3). Runs once, under the same
 * [MutationQueue] as import/save/deletion so it cannot race them, and is
 * idempotent by construction — re-running it after a kill at any point
 * converges to the same end state (design: "Recovery is idempotent").
 *
 * Never infers that missing data means "safe to delete" or "safe to
 * recreate" (design §11): a `READY` row with an unreadable blob becomes
 * `CORRUPT`, not deleted; a `CORRUPT` row is retained until a user
 * confirms deletion; orphaned files are removed only after every
 * row has been reconciled successfully.
 */
class RecoveryRepository(
    private val paths: VaultPaths,
    private val database: OpenLifeDatabase,
    keystoreWrapper: KeystoreWrapper,
    private val mutationQueue: MutationQueue,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileOps: ArtefactFileOps = ArtefactFileOps.Default,
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun recover(): RecoveryReport = withContext(ioDispatcher) {
        mutationQueue.withMutation {
            // A process death can leave an OCR operation marked RUNNING. Recovery
            // must make that state explicit before reconciling Sources; it can
            // never be treated as a confirmed result after restart.
            val staleOcrRevisions = database.ocrDao().markRunningStale()
            var cleanedStaged = 0
            var confirmedReady = 0
            var markedCorrupt = 0
            var resumedDeletions = 0
            var unrecognisedRows = 0

            val rows = database.sourceDao().findAll()
            val referencedIds = mutableSetOf<UUID>()
            var allRowsReconciled = true

            for (entity in rows) {
                val source = entity.toDomain()
                referencedIds += source.id

                when (source.state) {
                    SourceState.STAGED -> {
                        if (removeArtefactFiles(source.id)) {
                            database.sourceDao().deleteById(source.id.toString())
                            cleanedStaged++
                        } else {
                            // Keep the STAGED row as durable retry state when a
                            // provider-owned artefact cannot be removed. A later
                            // recovery pass can retry without losing the row's
                            // ownership record or treating its files as orphans.
                            allRowsReconciled = false
                        }
                    }

                    SourceState.READY -> when (blobFraming(source.id)) {
                        Framing.PLAUSIBLE -> confirmedReady++

                        Framing.BROKEN -> {
                            val marked = database.sourceDao()
                                .compareAndSetState(source.id.toString(), SourceState.READY, SourceState.CORRUPT)
                            if (marked == StateTransition.APPLIED) markedCorrupt++
                        }

                        // An I/O error says nothing about the stored bytes.
                        Framing.UNREADABLE -> Unit
                    }

                    SourceState.DELETING -> {
                        if (removeArtefactFiles(source.id)) {
                            database.sourceDao().deleteById(source.id.toString())
                            resumedDeletions++
                        } else {
                            // DELETING is intentionally retained until both
                            // possible artefact paths are gone; hiding the row
                            // would make a failed cleanup impossible to retry.
                            allRowsReconciled = false
                        }
                    }

                    SourceState.CORRUPT -> {
                        // Retained until a user confirms deletion; no
                        // automatic action.
                    }

                    // Written by another build: never shown, never deleted, and
                    // its files are referenced, so not orphans (P1-18-R14).
                    SourceState.UNRECOGNISED -> unrecognisedRows++
                }
            }

            val (removedOrphans, keptBlobsWithoutRows) = cleanOrphans(rows.size, allRowsReconciled, referencedIds)

            RecoveryReport(
                cleanedStaged = cleanedStaged,
                confirmedReady = confirmedReady,
                markedCorrupt = markedCorrupt,
                resumedDeletions = resumedDeletions,
                removedOrphanFiles = removedOrphans,
                markedStaleOcrRevisions = staleOcrRevisions,
                unrecognisedRows = unrecognisedRows,
                keptBlobsWithoutRows = keptBlobsWithoutRows,
            )
        }
    }

    /**
     * Settings > Verify all items (P1-14-R3): deep-authenticates every READY
     * and CORRUPT item. Only a genuine authentication failure or a missing
     * blob marks a READY item CORRUPT; a CORRUPT item whose blob now fully
     * authenticates is restored to READY (P1-18-R3), since an earlier verdict
     * may have come from a transient Keystore error. A Keystore or I/O failure
     * changes nothing and is counted as [VerifyReport.transient]. Every state
     * change is a compare-and-set. Each item is checked in its own mutation,
     * so imports and deletions are not held off for the whole pass.
     */
    suspend fun verifyAll(): VerifyReport = withContext(ioDispatcher) {
        var verified = 0
        var markedCorrupt = 0
        var transient = 0
        var restored = 0
        val checked = setOf(SourceState.READY.name, SourceState.CORRUPT.name)
        val ids = mutationQueue.withReadLease {
            database.sourceDao().findAll().filter { it.state in checked }.map { it.id }
        }
        for (id in ids) {
            mutationQueue.withMutation {
                val source = database.sourceDao().findById(id)?.toDomain()
                if (source == null || source.state.name !in checked) return@withMutation
                val check = authenticator.check(source, paths.blobFile(source.id))
                if (check is ArtefactCheck.Verified) check.plaintext.fill(0)
                when {
                    check == ArtefactCheck.Transient -> transient++

                    source.state == SourceState.READY && check is ArtefactCheck.Verified -> verified++

                    source.state == SourceState.READY -> {
                        val marked = database.sourceDao().compareAndSetState(id, SourceState.READY, SourceState.CORRUPT)
                        if (marked == StateTransition.APPLIED) markedCorrupt++
                    }

                    check is ArtefactCheck.Verified -> {
                        val back = database.sourceDao().compareAndSetState(id, SourceState.CORRUPT, SourceState.READY)
                        if (back == StateTransition.APPLIED) restored++
                    }

                    // CORRUPT and still corrupt or missing: unchanged.
                    else -> Unit
                }
            }
        }
        VerifyReport(verified = verified, markedCorrupt = markedCorrupt, transient = transient, restored = restored)
    }

    /**
     * The startup check (P1-14-R3): the blob exists and its envelope framing
     * is consistent with its length. Reads [EnvelopeCodec.FRAMING_PREFIX_BYTES]
     * bytes and never touches Keystore, so cold start does not scale with
     * vault size and a Keystore hiccup cannot mark anything CORRUPT. Deep
     * authentication happens on first read and in [verifyAll].
     */
    private fun blobFraming(sourceId: UUID): Framing {
        val blob = paths.blobFile(sourceId)
        if (!blob.exists()) return Framing.BROKEN
        return try {
            val prefix = ByteArray(EnvelopeCodec.FRAMING_PREFIX_BYTES)
            val read = FileInputStream(blob).use { input ->
                var total = 0
                while (total < prefix.size) {
                    val n = input.read(prefix, total, prefix.size - total)
                    if (n < 0) break
                    total += n
                }
                total
            }
            EnvelopeCodec.checkFraming(prefix.copyOf(read), blob.length())
            Framing.PLAUSIBLE
        } catch (_: EnvelopeCodec.MalformedEnvelopeException) {
            Framing.BROKEN
        } catch (_: IOException) {
            Framing.UNREADABLE
        }
    }

    private enum class Framing { PLAUSIBLE, BROKEN, UNREADABLE }

    /**
     * Light recovery for a running process (P1-01-R2): removes every STAGED
     * import except [activeSourceId], with its stage and blob files. READY,
     * DELETING and CORRUPT rows and unreferenced files are left to [recover].
     * A row whose files cannot be removed stays for a later pass. Returns the
     * number removed, or null without waiting when a mutation is in progress
     * (P1-01-R3: try again on the next foreground).
     */
    suspend fun cleanAbandonedStages(activeSourceId: UUID?): Int? = cleanAbandonedStages { activeSourceId }

    /**
     * As above, reading the active import inside the mutation so a prepare
     * that completes just before cannot have its fresh stage mistaken for an
     * abandoned one. Skips (null) while an import is still preparing.
     */
    suspend fun cleanAbandonedStages(importSlot: ImportSlot): Int? {
        // Checked before trying the queue as well as inside it (P1-18-R6, C0-R12 "skips").
        if (importSlot.isPreparing) return null
        return cleanAbandonedStages { if (importSlot.isPreparing) SKIP else importSlot.activeSourceId }
    }

    private suspend fun cleanAbandonedStages(active: () -> Any?): Int? = withContext(ioDispatcher) {
        mutationQueue.tryMutation {
            val activeNow = active()
            if (activeNow === SKIP) return@tryMutation null
            var cleaned = 0
            database.sourceDao().findAll()
                .filter { it.state == SourceState.STAGED.name && it.id != activeNow?.toString() }
                .forEach { row ->
                    val id = UUID.fromString(row.id)
                    if (removeArtefactFiles(id)) {
                        database.sourceDao().deleteById(row.id)
                        cleaned++
                    }
                }
            cleaned
        }
    }

    private companion object {
        val SKIP = Any()
    }

    private fun removeArtefactFiles(sourceId: UUID): Boolean = fileOps.deleteArtefacts(paths, sourceId)

    /**
     * Returns (removed orphan files, blobs kept because there are no rows).
     * Orphan deletion is safe only after every row was reconciled; if a
     * cleanup failed, all unreferenced files wait for the next pass. With zero
     * rows and saved originals present nothing is removed and the kept blobs
     * are reported, so the app refuses to show an empty vault (review R-08).
     */
    private fun cleanOrphans(rowCount: Int, allRowsReconciled: Boolean, referencedIds: Set<UUID>): Pair<Int, Int> {
        val artefactNames = paths.artefactsDir.list()?.toList().orEmpty()
        if (!OrphanPolicy.mayRemoveOrphans(rowCount, artefactNames)) {
            return 0 to OrphanPolicy.blobCount(artefactNames)
        }
        return (if (allRowsReconciled) removeUnreferencedArtefactFiles(referencedIds) else 0) to 0
    }

    /**
     * Deletes only `.stage`/`.blob` files whose UUID has no corresponding
     * row, and only after every row above has already been reconciled -
     * never as a first pass, so a mid-recovery failure elsewhere cannot
     * cause a still-needed file to look "unreferenced".
     */
    private fun removeUnreferencedArtefactFiles(referencedIds: Set<UUID>): Int {
        val files = paths.artefactsDir.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            val name = file.name
            val uuidPart = name.substringBeforeLast('.', missingDelimiterValue = "")
            val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            if (extension == "stage" || extension == "blob") {
                val id = runCatching { UUID.fromString(uuidPart) }.getOrNull()
                val orphan = id != null && id !in referencedIds && file.exists()
                if (orphan && fileOps.deleteIfExists(file)) removed++
            }
        }
        return removed
    }
}
