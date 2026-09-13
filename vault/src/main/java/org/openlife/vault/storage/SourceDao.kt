package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Minimal Stage 2 access needed to prove the schema and its READY invariant
 * work end to end. Import/save/recovery/deletion queries land in Stage 3+
 * alongside the repository that owns the mutation queue (design §11-§12).
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
}
