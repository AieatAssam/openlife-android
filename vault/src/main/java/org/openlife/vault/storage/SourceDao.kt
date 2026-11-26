package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for source rows and the READY invariant. The repositories own
 * import, save, recovery, and deletion mutations (design §11-§12).
 *
 * State changes are compare-and-set statements ([transitionState],
 * [updateStagedMetadata]) so a stale snapshot can never be written back over
 * a row another writer changed (P1-18-R13). The one remaining @Update:
 * - [update]: not called by production code. Test fixtures use it to write
 *   arbitrary rows, including deliberately invalid ones that the READY
 *   trigger must reject.
 */
@Dao
interface SourceDao {
    @Insert
    suspend fun insert(source: SourceEntity)

    /** Test fixtures only; see the class KDoc. Production state changes use [transitionState]. */
    @Update
    suspend fun update(source: SourceEntity)

    /**
     * Records the validated metadata of a STAGED row (design §11 "Prepare");
     * returns 0 when the row is no longer STAGED, and then nothing is written.
     */
    @Query(
        """
        UPDATE sources SET mimeType = :mimeType, byteCount = :byteCount, sha256 = :sha256, width = :width,
            height = :height, orientation = :orientation, artefactVersion = :artefactVersion
        WHERE id = :id AND state = 'STAGED'
        """,
    )
    @Suppress("LongParameterList") // one column per validated field
    suspend fun updateStagedMetadata(
        id: String,
        mimeType: String,
        byteCount: Long,
        sha256: ByteArray,
        width: Int,
        height: Int,
        orientation: String,
        artefactVersion: Int,
    ): Int

    /** Compare-and-set state change; returns the number of rows changed (0 or 1). */
    @Query("UPDATE sources SET state = :to WHERE id = :id AND state = :from")
    suspend fun transitionState(id: String, from: String, to: String): Int

    /**
     * P2-07-R3: the user's content-kind label and the time they gave it
     * (actor USER). Compare-and-set on READY: a row that is not saved, or no
     * longer exists, is not labelled. Never touches `assessmentState`.
     */
    @Query("UPDATE sources SET userContentKind = :kind, userContentKindAt = :at WHERE id = :id AND state = 'READY'")
    suspend fun setUserContentKind(id: String, kind: String?, at: Long): Int

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun findById(id: String): SourceEntity?

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Removes a row only while it is STAGED; returns 0 for any other state (P1-18 review R-06). */
    @Query("DELETE FROM sources WHERE id = :id AND state = 'STAGED'")
    suspend fun deleteStaged(id: String): Int

    @Query("SELECT * FROM sources")
    suspend fun findAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE state = 'READY' AND byteCount = :byteCount AND sha256 = :sha256 LIMIT 1")
    suspend fun findReadyDuplicate(byteCount: Long, sha256: ByteArray): SourceEntity?

    /** Saved, corrupt, and pending-deletion rows; STAGED remains transient and hidden. */
    @Query("SELECT * FROM sources WHERE state IN ('READY', 'CORRUPT', 'DELETING') ORDER BY importedAt ASC")
    fun observeVisibleSources(): Flow<List<SourceEntity>>

    /** P1-20-R2: one visible source by id (the same states as the list); null once it is gone. */
    @Query("SELECT * FROM sources WHERE id = :id AND state IN ('READY', 'CORRUPT', 'DELETING')")
    fun observeVisibleSource(id: String): Flow<SourceEntity?>

    /** P1-08-R4 (review K-03): counts per stored state; reads the state column only, never row content. */
    @Query(COUNT_SOURCES_BY_STATE)
    suspend fun countByState(): List<StateCount>
}

/** Outcome of a compare-and-set state transition (P1-18-R13). */
enum class StateTransition {
    /** Exactly one row moved from the expected state to the new one. */
    APPLIED,

    /** The row was not in the expected state (or no longer exists); nothing was written. */
    LOST_RACE,
}

/** Moves [id] from [from] to [to] only if it is still in [from]. */
suspend fun SourceDao.compareAndSetState(
    id: String,
    from: org.openlife.vault.model.SourceState,
    to: org.openlife.vault.model.SourceState,
): StateTransition {
    val unrecognised = org.openlife.vault.model.SourceState.UNRECOGNISED
    check(from != unrecognised && to != unrecognised) {
        "an unrecognised state is never written"
    }
    return if (transitionState(id, from.name, to.name) == 1) StateTransition.APPLIED else StateTransition.LOST_RACE
}
