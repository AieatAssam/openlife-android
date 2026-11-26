package org.openlife.vault.storage

import androidx.room.ColumnInfo
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
    /** C2-R16: true iff all four box columns are non-null. */
    @ColumnInfo(defaultValue = "0")
    val hasEvidenceRegion: Boolean = false,
    /** C2-R17: [SpanScriptStatus] of [text]; UNKNOWN is the column default only. */
    @ColumnInfo(defaultValue = "UNKNOWN")
    val scriptStatus: String = SpanScriptStatus.UNKNOWN.name,
)
