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
