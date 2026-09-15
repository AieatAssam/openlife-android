package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ocr_revisions",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId")],
)
data class OcrRevisionEntity(
    @androidx.room.PrimaryKey val id: String,
    val sourceId: String,
    val state: String,
    val engineId: String,
    val modelVersion: String,
    val orientation: String,
    val sourceDigest: ByteArray,
    val startedAt: Long,
    val extractedAt: Long?,
    val reviewState: String,
    val failureReason: String?,
    val charCount: Int,
    val spanCount: Int,
)
