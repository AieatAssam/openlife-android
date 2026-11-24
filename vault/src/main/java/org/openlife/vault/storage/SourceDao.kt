package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for source rows and the READY invariant. The repositories own
 * import, save, recovery, and deletion mutations (design §11-§12).
 */
@Dao
interface SourceDao {
    @Insert
    suspend fun insert(source: SourceEntity)

    @Update
    suspend fun update(source: SourceEntity)

    /** Compare-and-set state change; returns the number of rows changed (0 or 1). */
    @Query("UPDATE sources SET state = :to WHERE id = :id AND state = :from")
    suspend fun transitionState(id: String, from: String, to: String): Int

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun findById(id: String): SourceEntity?

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM sources")
    suspend fun findAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE state = 'READY' AND byteCount = :byteCount AND sha256 = :sha256 LIMIT 1")
    suspend fun findReadyDuplicate(byteCount: Long, sha256: ByteArray): SourceEntity?

    /** Saved, corrupt, and pending-deletion rows; STAGED remains transient and hidden. */
    @Query("SELECT * FROM sources WHERE state IN ('READY', 'CORRUPT', 'DELETING') ORDER BY importedAt ASC")
    fun observeVisibleSources(): Flow<List<SourceEntity>>
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
): StateTransition = TODO("P1-18-R13")
