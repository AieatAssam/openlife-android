package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ocr_user_revisions",
    foreignKeys = [
        ForeignKey(
            entity = OcrRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OcrSpanEntity::class,
            parentColumns = ["id"],
            childColumns = ["spanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId"), Index("spanId"), Index(value = ["supersedesId"], unique = true)],
)
data class OcrUserRevisionEntity(
    @androidx.room.PrimaryKey val id: String,
    val revisionId: String,
    val spanId: String?,
    val correctedText: String,
    val createdAt: Long,
    val actor: String,
    /** C2-R15: the correction this one supersedes (same reading, same span); not a foreign key. */
    val supersedesId: String? = null,
)
