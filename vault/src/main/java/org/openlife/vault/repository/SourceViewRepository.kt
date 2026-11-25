package org.openlife.vault.repository

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.openlife.vault.crypto.KeystoreWrapper
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState
import org.openlife.vault.model.UserContentKind
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SourceDao
import org.openlife.vault.storage.StateTransition
import org.openlife.vault.storage.VaultPaths
import org.openlife.vault.storage.compareAndSetState
import org.openlife.vault.storage.toDomain
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val authenticator = ArtefactAuthenticator(keystoreWrapper)

    /** Saved, corrupt, and pending-deletion sources, ordered by import time. */
    fun observeVisibleSources(): Flow<List<Source>> =
        database.sourceDao().observeVisibleSources().map { entities -> entities.map { it.toDomain() } }

    suspend fun findSource(sourceId: UUID): Source? = database.sourceDao().findById(sourceId.toString())?.toDomain()

    /**
     * P2-07-R3: records the user's answer to "Is this a message or request
     * from someone?" with the time ([clock]) they gave it. [kind] null is
     * "Not sure", still attributed by its timestamp. Returns false when the
     * source is not READY (compare-and-set) or the write fails (see
     * [writeUserContentKind]). The assessment state is never touched: a user
     * label is routing context, not proof of safety (design §6).
     */
    suspend fun setUserContentKind(sourceId: UUID, kind: UserContentKind?): Boolean = withContext(ioDispatcher) {
        check(kind != UserContentKind.UNRECOGNISED) { "an unrecognised content kind is never written" }
        mutationQueue.withMutation {
            database.sourceDao().writeUserContentKind(sourceId, kind, clock())
        }
    }

    /** The not-yet-saved stage, authenticated from the encrypted file on disk (design §11 step 4). */
    suspend fun loadStagePreviewBytes(sourceId: UUID): ByteArray? = withContext(ioDispatcher) {
        mutationQueue.withReadLease {
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain() ?: return@withReadLease null
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
        mutationQueue.withReadLease {
            val source = database.sourceDao().findById(sourceId.toString())?.toDomain()
            if (source == null || source.state != SourceState.READY) return@withReadLease ReadyReadResult.Unavailable
            when (val check = authenticator.check(source, paths.blobFile(sourceId))) {
                is ArtefactCheck.Verified -> ReadyReadResult.Loaded(check.plaintext)

                // Compare-and-set (P1-18-R13); either way the original is not shown.
                ArtefactCheck.Corrupt -> when (
                    database.sourceDao().compareAndSetState(sourceId.toString(), SourceState.READY, SourceState.CORRUPT)
                ) {
                    StateTransition.APPLIED -> ReadyReadResult.Corrupt

                    // The row left READY since it was read (deleted or already
                    // marked): report it as no longer readable (review R-06).
                    StateTransition.LOST_RACE -> ReadyReadResult.Unavailable
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

/**
 * P2-07 review T-03: the label write as one compare-and-set. A database
 * error (for example a write queued behind a vault reset that closed the
 * database) is reported as "not saved" (false), never thrown into the UI;
 * the viewer keeps showing what is stored. Cancellation is rethrown. The
 * label and the identifier are never logged.
 */
internal suspend fun SourceDao.writeUserContentKind(sourceId: UUID, kind: UserContentKind?, at: Long): Boolean = try {
    setUserContentKind(sourceId.toString(), kind?.name, at) == 1
} catch (cancelled: CancellationException) {
    // A CancellationException is an IllegalStateException; it is never "not saved".
    throw cancelled
} catch (_: SQLiteException) {
    false
} catch (_: IllegalStateException) {
    // Room and SQLCipher report a closed database or connection pool this way.
    false
}
