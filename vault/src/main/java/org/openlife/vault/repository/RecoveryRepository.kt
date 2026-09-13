package org.openlife.vault.repository

import java.util.UUID
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity

/**
 * Startup recovery per design §11's table. Runs once, under the same
 * [MutationQueue] as import/save/deletion so it cannot race them, and is
 * idempotent by construction — re-running it after a kill at any point
 * converges to the same end state (design: "Recovery is idempotent").
 *
 * Never infers that missing data means "safe to delete" or "safe to
 * recreate" (design §11): a `READY` row with an unreadable blob becomes
 * `CORRUPT`, not deleted; a `CORRUPT` row is retained until a user
 * confirms deletion (Stage 5); orphaned files are removed only after every
 * row has been reconciled successfully.
 */
class RecoveryRepository(
    private val paths: VaultPaths,
    private val database: OpenLifeDatabase,
    keystoreWrapper: KeystoreWrapper,
    private val mutationQueue: MutationQueue,
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    suspend fun recover(): RecoveryReport = mutationQueue.acquire {
        var cleanedStaged = 0
        var confirmedReady = 0
        var markedCorrupt = 0
        var resumedDeletions = 0

        val rows = database.sourceDao().findAll()
        val referencedIds = mutableSetOf<UUID>()

        for (entity in rows) {
            val source = entity.toDomain()
            referencedIds += source.id

            when (source.state) {
                SourceState.STAGED -> {
                    removeArtefactFiles(source.id)
                    database.sourceDao().deleteById(source.id.toString())
                    cleanedStaged++
                }

                SourceState.READY -> {
                    if (authenticator.authenticates(source, paths.blobFile(source.id))) {
                        confirmedReady++
                    } else {
                        database.sourceDao().update(source.copy(state = SourceState.CORRUPT).toEntity())
                        markedCorrupt++
                    }
                }

                SourceState.DELETING -> {
                    removeArtefactFiles(source.id)
                    database.sourceDao().deleteById(source.id.toString())
                    resumedDeletions++
                }

                SourceState.CORRUPT -> {
                    // Retained until a user confirms deletion (Stage 5); no
                    // automatic action.
                }
            }
        }

        val removedOrphans = removeUnreferencedArtefactFiles(referencedIds)

        RecoveryReport(
            cleanedStaged = cleanedStaged,
            confirmedReady = confirmedReady,
            markedCorrupt = markedCorrupt,
            resumedDeletions = resumedDeletions,
            removedOrphanFiles = removedOrphans,
        )
    }

    private fun removeArtefactFiles(sourceId: UUID) {
        paths.stageFile(sourceId).delete()
        paths.blobFile(sourceId).delete()
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
            if (extension != "stage" && extension != "blob") continue
            val id = try {
                UUID.fromString(uuidPart)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (id !in referencedIds && file.delete()) removed++
        }
        return removed
    }
}
