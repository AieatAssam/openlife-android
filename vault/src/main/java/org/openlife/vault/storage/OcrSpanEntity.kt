package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ocr_spans",
    foreignKeys = [
        ForeignKey(
            entity = OcrRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId"), Index(value = ["revisionId", "ordinal"], unique = true)],
)
data class OcrSpanEntity(
    @androidx.room.PrimaryKey val id: String,
    val revisionId: String,
    val ordinal: Int,
    val text: String,
    val confidence: Float?,
    val coordinateSystem: String,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
)
