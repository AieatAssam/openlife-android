package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Per-span review metadata (P2-06-R5). Append-only; deleting the span cascades these rows. */
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
    // One row per (span, sequence); the index also serves the spanId foreign key.
    indices = [Index(value = ["spanId", "sequence"], unique = true)],
)
data class OcrSpanReviewEntity(
    @androidx.room.PrimaryKey val id: String,
    val spanId: String,
    val reviewState: String,
    val createdAt: Long,
    /** Per-span, monotonic, assigned by [OcrSpanReviewDao.append]; decides which row is current (P2-06-R12). */
    val sequence: Long,
)
