package org.openlife.vault.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.toDomain
import org.openlife.vault.storage.toEntity
import java.util.UUID

/**
 * The "view" half of design §7's repository layout: the source list, the
 * intake preview, and the viewer all read through here rather than
 * touching Keystore/file paths directly. Every returned byte array has
 * already been authenticated (design §8: "Opening an entry verifies and
 * displays the saved artefact"; §11: "Authenticate and preview that
 * encrypted stage, not a second provider read").
 */
class SourceViewRepository(
    private val paths: VaultPaths,
    private val database: OpenLifeDatabase,
    keystoreWrapper: KeystoreWrapper,
    private val mutationQueue: MutationQueue,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    /** Saved, corrupt, and pending-deletion sources, ordered by import time. */
    fun observeVisibleSources(): Flow<List<Source>> =
        database.sourceDao().observeVisibleSources().map { entities -> entities.map { it.toDomain() } }

    suspend fun findSource(sourceId: UUID): Source? = database.sourceDao().findById(sourceId.toString())?.toDomain()

    /** The not-yet-saved stage, authenticated from the encrypted file on disk (design §11 step 4). */
    suspend fun loadStagePreviewBytes(sourceId: UUID): ByteArray? = withContext(ioDispatcher) {
        mutationQueue.acquire {
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain() ?: return@acquire null
            authenticator.decryptAndVerify(source, paths.stageFile(sourceId))
        }
    }

    /**
     * A saved (READY) source's original bytes, authenticated before use
     * (design §8), with the reason when they cannot be shown. A genuine
     * authentication or digest failure marks the row CORRUPT under the
     * mutation queue, keeping its artefact and wrapped key for diagnosis
     * (design §9). A Keystore or I/O failure marks nothing (P1-13-R5).
     */
    suspend fun readReadyBytes(sourceId: UUID): ReadyReadResult = withContext(ioDispatcher) {
        mutationQueue.acquire {
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
            if (source == null || source.state != SourceState.READY) return@acquire ReadyReadResult.Unavailable
            when (val check = authenticator.check(source, paths.blobFile(sourceId))) {
                is ArtefactCheck.Verified -> ReadyReadResult.Loaded(check.plaintext)

                ArtefactCheck.Corrupt -> {
                    database.sourceDao().update(source.copy(state = SourceState.CORRUPT).toEntity())
                    ReadyReadResult.Corrupt
                }

                ArtefactCheck.Missing -> ReadyReadResult.Missing

                ArtefactCheck.Transient -> ReadyReadResult.Transient
            }
        }
    }

    /** Convenience for callers that only need the authenticated bytes. */
    suspend fun loadReadyBytes(sourceId: UUID): ByteArray? =
        (readReadyBytes(sourceId) as? ReadyReadResult.Loaded)?.bytes
}
