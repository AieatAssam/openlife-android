package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The SQL of [FactDao], as constants so the host-SQLite tests run exactly
 * the statements Room runs (ADR-0010 pattern). Writes are compare-and-set or
 * insert-select statements that compute the next number inside the statement
 * (P1-18-R13, VS-1); there is no @Update and no @Insert of an entity.
 */
object FactSql {
    const val INSERT_FACT = """
        INSERT INTO facts(id, sourceId, kind, ordinal, currentRevisionId, createdAt)
        SELECT :id, :sourceId, :kind, COALESCE(MAX(ordinal), 0) + 1, NULL, :createdAt
        FROM facts WHERE sourceId = :sourceId AND kind = :kind
        """

    const val INSERT_REVISION = """
        INSERT INTO fact_revisions(id, factId, revisionNo, origin, kind, valueJson, canonicalText, rawText, status,
            staleReason, staleAt, confidence, methodId, methodVersion, locale, createdAt, supersedesRevisionId)
        SELECT :id, :factId, COALESCE(MAX(revisionNo), 0) + 1, :origin, :kind, :valueJson, :canonicalText, :rawText,
            :status, NULL, NULL, NULL, :methodId, :methodVersion, :locale, :createdAt, :supersedesRevisionId
        FROM fact_revisions WHERE factId = :factId
        """

    const val INSERT_EVIDENCE = """
        INSERT INTO fact_evidence(id, revisionId, ordinal, role, sourceId, ocrRevisionId, ocrSpanId, ocrUserRevisionId,
            textStart, textEnd, regionLeft, regionTop, regionRight, regionBottom, regionCoordinateSystem)
        SELECT :id, :revisionId,
            (SELECT COALESCE(MAX(e.ordinal) + 1, 0) FROM fact_evidence e WHERE e.revisionId = :revisionId),
            :role, :sourceId, s.revisionId, s.id, :ocrUserRevisionId, :textStart, :textEnd,
            s.`left`, s.top, s.`right`, s.bottom, s.coordinateSystem
        FROM ocr_spans s WHERE s.id = :ocrSpanId
        """

    const val APPEND_REVIEW_EVENT = """
        INSERT INTO review_events(id, revisionId, sequence, decision, createdAt)
        SELECT :id, :revisionId, COALESCE(MAX(sequence), 0) + 1, :decision, :createdAt
        FROM review_events WHERE revisionId = :revisionId
        """

    const val MOVE_CURRENT_REVISION =
        "UPDATE facts SET currentRevisionId = :revisionId WHERE id = :factId AND currentRevisionId IS :expectedRevisionId"

    const val SET_STATUS = "UPDATE fact_revisions SET status = :to WHERE id = :id AND status = :from"

    const val MARK_STALE = """
        UPDATE fact_revisions SET status = 'STALE', staleReason = :reason, staleAt = :staleAt
        WHERE id IN (:ids) AND status IN ('UNREVIEWED', 'CONFIRMED', 'REJECTED', 'CLARIFICATION_NEEDED')
        """

    const val BUMP_FACT_INPUT_EPOCH = "UPDATE sources SET factInputEpoch = factInputEpoch + 1 WHERE id = :sourceId"

    const val FIND_FACT = "SELECT id, sourceId, kind, ordinal, currentRevisionId, createdAt FROM facts WHERE id = :id"

    const val FIND_REVISION = """
        SELECT id, factId, revisionNo, origin, kind, valueJson, canonicalText, rawText, status, staleReason, staleAt,
            confidence, methodId, methodVersion, locale, createdAt, supersedesRevisionId
        FROM fact_revisions WHERE id = :id
        """

    const val SOURCE_STATE = "SELECT state FROM sources WHERE id = :sourceId"

    const val COUNT_CURRENT_REVISIONS =
        "SELECT COUNT(*) FROM facts WHERE sourceId = :sourceId AND currentRevisionId IS NOT NULL"

    const val COUNT_VALUE_EVIDENCE =
        "SELECT COUNT(*) FROM fact_evidence WHERE revisionId = :revisionId AND role = 'VALUE'"

    const val BASIS_OF = """
        SELECT dependencyId FROM dependency_edges
        WHERE edgeKind = 'BASIS' AND dependentKind = 'FACT_REVISION' AND dependentId = :revisionId
        """

    const val DEPENDENTS_OF_SPAN = """
        SELECT DISTINCT dependentKind AS kind, dependentId AS id FROM dependency_edges
        WHERE dependencyKind = 'OCR_SPAN' AND dependencyId = :spanId
        """

    const val DEPENDENTS_OF_READING = """
        SELECT DISTINCT e.dependentKind AS kind, e.dependentId AS id
        FROM dependency_edges e JOIN ocr_spans s ON e.dependencyKind = 'OCR_SPAN' AND e.dependencyId = s.id
        WHERE s.revisionId = :ocrRevisionId
        """

    const val DEPENDENTS_OF = """
        SELECT DISTINCT dependentKind AS kind, dependentId AS id FROM dependency_edges
        WHERE dependencyKind = :kind AND dependencyId IN (:ids)
        """

    const val SOURCE_OF_SPAN =
        "SELECT r.sourceId FROM ocr_spans s JOIN ocr_revisions r ON r.id = s.revisionId WHERE s.id = :spanId"

    const val SOURCE_OF_READING = "SELECT sourceId FROM ocr_revisions WHERE id = :ocrRevisionId"

    const val OBSERVE_CURRENT_FACTS = """
        SELECT r.*, f.sourceId AS factSourceId, f.ordinal AS factOrdinal, f.createdAt AS factCreatedAt,
            f.currentRevisionId AS factCurrentRevisionId
        FROM facts f JOIN fact_revisions r ON r.id = f.currentRevisionId
            JOIN sources s ON s.id = f.sourceId
        WHERE f.sourceId = :sourceId AND s.state <> 'DELETING' ORDER BY f.kind ASC, f.ordinal ASC
        """
}

/** One node reached over `dependency_edges` (C2-R19). */
data class DependencyNode(val kind: String, val id: String)

/** A fact joined with the revision its `currentRevisionId` names. */
data class CurrentFactRow(
    @Embedded val revision: FactRevisionEntity,
    val factSourceId: String,
    val factOrdinal: Int,
    val factCreatedAt: Long,
    val factCurrentRevisionId: String,
)

/**
 * C2 §4 tables. Writes are reached only through FactRepository (and, from
 * P3-04, FactExtractionRepository), under the mutation queue, one transaction
 * per operation (C2-R52). Nothing here writes `dependency_edges` (C2-R18).
 */
@Suppress("TooManyFunctions") // one statement per operation (P1-18-R13)
@Dao
interface FactDao {
    @Query(FactSql.INSERT_FACT)
    suspend fun insertFact(id: String, sourceId: String, kind: String, createdAt: Long)

    @Suppress("LongParameterList") // one column per parameter
    @Query(FactSql.INSERT_REVISION)
    suspend fun insertRevision(
        id: String,
        factId: String,
        origin: String,
        kind: String,
        valueJson: String,
        canonicalText: String,
        rawText: String?,
        status: String,
        methodId: String,
        methodVersion: String?,
        locale: String?,
        createdAt: Long,
        supersedesRevisionId: String?,
    )

    /** Copies the span's box and reading verbatim (C2-R8); the next ordinal is computed in the statement. */
    @Suppress("LongParameterList") // one column per parameter
    @Query(FactSql.INSERT_EVIDENCE)
    suspend fun insertEvidence(
        id: String,
        revisionId: String,
        role: String,
        sourceId: String,
        ocrSpanId: String,
        ocrUserRevisionId: String?,
        textStart: Int?,
        textEnd: Int?,
    )

    @Query(FactSql.APPEND_REVIEW_EVENT)
    suspend fun appendReviewEvent(id: String, revisionId: String, decision: String, createdAt: Long)

    /** Compare-and-set of the pointer; 0 when it no longer names [expectedRevisionId]. */
    @Query(FactSql.MOVE_CURRENT_REVISION)
    suspend fun moveCurrentRevision(factId: String, expectedRevisionId: String?, revisionId: String): Int

    /** Compare-and-set of a status; 0 when the revision is no longer in [from]. */
    @Query(FactSql.SET_STATUS)
    suspend fun setStatus(id: String, from: String, to: String): Int

    /** Marks the recognised, not yet STALE revisions among [ids]; returns how many. */
    @Query(FactSql.MARK_STALE)
    suspend fun markStale(ids: List<String>, reason: String, staleAt: Long): Int

    @Query(FactSql.BUMP_FACT_INPUT_EPOCH)
    suspend fun bumpFactInputEpoch(sourceId: String): Int

    @Query(FactSql.FIND_FACT)
    suspend fun findFact(id: String): FactEntity?

    @Query(FactSql.FIND_REVISION)
    suspend fun findRevision(id: String): FactRevisionEntity?

    @Query(FactSql.SOURCE_STATE)
    suspend fun sourceState(sourceId: String): String?

    @Query(FactSql.COUNT_CURRENT_REVISIONS)
    suspend fun countCurrentRevisions(sourceId: String): Int

    @Query(FactSql.COUNT_VALUE_EVIDENCE)
    suspend fun countValueEvidence(revisionId: String): Int

    @Query(FactSql.BASIS_OF)
    suspend fun basisOf(revisionId: String): String?

    @Query(FactSql.DEPENDENTS_OF_SPAN)
    suspend fun dependentsOfSpan(spanId: String): List<DependencyNode>

    @Query(FactSql.DEPENDENTS_OF_READING)
    suspend fun dependentsOfReading(ocrRevisionId: String): List<DependencyNode>

    @Query(FactSql.DEPENDENTS_OF)
    suspend fun dependentsOf(kind: String, ids: List<String>): List<DependencyNode>

    @Query(FactSql.SOURCE_OF_SPAN)
    suspend fun sourceOfSpan(spanId: String): String?

    @Query(FactSql.SOURCE_OF_READING)
    suspend fun sourceOfReading(ocrRevisionId: String): String?

    @Query(FactSql.OBSERVE_CURRENT_FACTS)
    fun observeCurrentFacts(sourceId: String): Flow<List<CurrentFactRow>>
}
