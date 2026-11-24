package org.openlife.vault.repository

import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.StateTransition
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.compareAndSetState
import org.openlife.vault.storage.toDomain
import java.io.File
import java.util.UUID

/** Decides whether a staged import is an exact duplicate of a saved original (P1-15-R4). */
internal class DuplicateVerifier(
    private val paths: VaultPaths,
    private val sourceDao: SourceDao,
    private val authenticator: ArtefactAuthenticator,
) {
    /**
     * Design §9: a digest match alone is not a duplicate. The existing READY
     * original must authenticate and its bytes must equal the new stage's.
     * - An existing blob that fails authentication is marked CORRUPT (kept
     *   for diagnosis) and the new import is saved in its place.
     * - A missing blob is left for recovery, and the new import is saved.
     * - A transient Keystore or I/O failure keeps the stage: the import
     *   fails honestly instead of discarding the user's new copy (P1-15-R4).
     */
    suspend fun check(source: Source, stageFile: File): DuplicateCheck {
        val candidate = sourceDao.findReadyDuplicate(source.byteCount!!, source.sha256!!)?.toDomain()
            ?: return DuplicateCheck.None
        return when (val existing = authenticator.check(candidate, paths.blobFile(candidate.id))) {
            is ArtefactCheck.Verified -> {
                val stage = authenticator.decryptAndVerify(source, stageFile)
                try {
                    if (stage != null && stage.contentEquals(existing.plaintext)) {
                        DuplicateCheck.Found(candidate.id)
                    } else if (stage == null) {
                        DuplicateCheck.Unverifiable
                    } else {
                        DuplicateCheck.None
                    }
                } finally {
                    existing.plaintext.fill(0)
                    stage?.fill(0)
                }
            }

            ArtefactCheck.Corrupt ->
                when (sourceDao.compareAndSetState(candidate.id.toString(), SourceState.READY, SourceState.CORRUPT)) {
                    // The tampered original is marked; the new import is saved (C0-R16).
                    StateTransition.APPLIED -> DuplicateCheck.None

                    // The candidate changed since it was read (review R-06):
                    // decide nothing, keep the stage for a retry.
                    StateTransition.LOST_RACE -> DuplicateCheck.Unverifiable
                }

            ArtefactCheck.Missing -> DuplicateCheck.None

            ArtefactCheck.Transient -> DuplicateCheck.Unverifiable
        }
    }
}

internal sealed interface DuplicateCheck {
    data object None : DuplicateCheck
    class Found(val existingId: UUID) : DuplicateCheck
    data object Unverifiable : DuplicateCheck
}
