package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Per-span review metadata (P2-06-R5). Deleting the span cascades these rows. */
@Entity(
    tableName = "ocr_span_reviews",
    foreignKeys = [
        ForeignKey(
            entity = OcrSpanEntity::class,
            parentColumns = ["id"],
            childColumns = ["spanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("spanId")],
)
data class OcrSpanReviewEntity(
    @androidx.room.PrimaryKey val id: String,
    val spanId: String,
    val reviewState: String,
    val createdAt: Long,
)
