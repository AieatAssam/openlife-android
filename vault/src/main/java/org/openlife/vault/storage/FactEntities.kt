package org.openlife.vault.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * C2 §4 tables (P3-02-R1). Foreign keys CASCADE only down one Source's
 * ownership tree (sources -> facts -> fact_revisions -> fact_evidence,
 * review_events; sources -> fact_extraction_runs) and are NO ACTION for every
 * reference out of it (C2-R20, D-6). The three pointer columns
 * (facts.currentRevisionId, sources.currentOcrRevisionId,
 * ocr_user_revisions.supersedesId) are deliberately not foreign keys; triggers
 * check them (FactSchemaTriggers). Room cannot declare CHECK constraints, so
 * every C2 CHECK is a trigger too.
 */

/** C2 §4.1. Immutable except `currentRevisionId` (C2-R3). */
@Entity(
    tableName = "facts",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId", "kind", "ordinal"], unique = true)],
)
data class FactEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val kind: String,
    val ordinal: Int,
    val currentRevisionId: String?,
    val createdAt: Long,
)

/** C2 §4.2. Only `status`, `staleReason` and `staleAt` ever change (C2-R4). */
@Entity(
    tableName = "fact_revisions",
    foreignKeys = [
        ForeignKey(
            entity = FactEntity::class,
            parentColumns = ["id"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FactRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["supersedesRevisionId"],
        ),
    ],
    indices = [Index(value = ["factId", "revisionNo"], unique = true), Index("supersedesRevisionId")],
)
data class FactRevisionEntity(
    @PrimaryKey val id: String,
    val factId: String,
    val revisionNo: Int,
    val origin: String,
    val kind: String,
    val valueJson: String,
    val canonicalText: String,
    val rawText: String?,
    val status: String,
    val staleReason: String?,
    val staleAt: Long?,
    val confidence: Double?,
    val methodId: String,
    val methodVersion: String?,
    val locale: String?,
    val createdAt: Long,
    val supersedesRevisionId: String?,
)

/** C2 §4.3: one line of evidence, with a verbatim snapshot of its line box (C2-R8). */
@Entity(
    tableName = "fact_evidence",
    foreignKeys = [
        ForeignKey(
            entity = FactRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = OcrRevisionEntity::class, parentColumns = ["id"], childColumns = ["ocrRevisionId"]),
        ForeignKey(entity = OcrSpanEntity::class, parentColumns = ["id"], childColumns = ["ocrSpanId"]),
        ForeignKey(entity = OcrUserRevisionEntity::class, parentColumns = ["id"], childColumns = ["ocrUserRevisionId"]),
    ],
    indices = [
        Index(value = ["revisionId", "ordinal"], unique = true),
        Index("sourceId"),
        Index("ocrRevisionId"),
        Index("ocrSpanId"),
        Index("ocrUserRevisionId"),
    ],
)
data class FactEvidenceEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val ordinal: Int,
    val role: String,
    val sourceId: String,
    val ocrRevisionId: String,
    val ocrSpanId: String,
    val ocrUserRevisionId: String?,
    val textStart: Int?,
    val textEnd: Int?,
    val regionLeft: Int?,
    val regionTop: Int?,
    val regionRight: Int?,
    val regionBottom: Int?,
    val regionCoordinateSystem: String?,
)

/** C2 §4.5: append-only (C2-R12); ordered by `sequence`, never the clock. */
@Entity(
    tableName = "review_events",
    foreignKeys = [
        ForeignKey(
            entity = FactRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["revisionId", "sequence"], unique = true)],
)
data class ReviewEventEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val sequence: Int,
    val decision: String,
    val createdAt: Long,
)

/** C2 §4.6: one row per finished run; holds no content (C2-R13). */
@Entity(
    tableName = "fact_extraction_runs",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = OcrRevisionEntity::class, parentColumns = ["id"], childColumns = ["ocrRevisionId"]),
    ],
    indices = [Index("sourceId"), Index("ocrRevisionId")],
)
data class FactExtractionRunEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val ocrRevisionId: String,
    val trigger: String,
    val rulesVersion: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: String,
    val skippedKinds: String?,
    val candidateCount: Int?,
    val createdRevisionCount: Int?,
    val skippedLineCount: Int?,
)

/**
 * C2 §4.8 (VS-2): the traversal index over evidence and basis links. Written
 * and deleted only by triggers (C2-R18); no repository statement touches it.
 */
@Entity(
    tableName = "dependency_edges",
    indices = [
        Index(
            value = ["edgeKind", "dependentKind", "dependentId", "dependencyKind", "dependencyId"],
            unique = true,
        ),
        Index(value = ["dependencyKind", "dependencyId"]),
        Index(value = ["dependentKind", "dependentId"]),
        Index("dependencySourceId"),
    ],
)
data class DependencyEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val edgeKind: String,
    val dependentKind: String,
    val dependentId: String,
    val dependentSourceId: String,
    val dependencyKind: String,
    val dependencyId: String,
    val dependencySourceId: String,
)
