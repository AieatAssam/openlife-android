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

    /** P3-05-R1: the evidence rows of one revision, in their stored order (C2-R34). */
    const val EVIDENCE_OF_REVISION = """
        SELECT id, revisionId, ordinal, role, sourceId, ocrRevisionId, ocrSpanId, ocrUserRevisionId, textStart, textEnd,
            regionLeft, regionTop, regionRight, regionBottom, regionCoordinateSystem
        FROM fact_evidence WHERE revisionId = :revisionId ORDER BY ordinal
        """

    /** P3-05-R1: the decision of the highest-sequence review event, or none if the revision was never reviewed. */
    const val LATEST_DECISION = """
        SELECT decision FROM review_events WHERE revisionId = :revisionId ORDER BY sequence DESC LIMIT 1
        """

    /**
     * P3-05-R1: the outcome of the newest extraction run against the source's current reading (C2-R39). Runs against
     * an older reading do not count, so a stale reading never reports a checked state.
     */
    const val EXTRACTION_OUTCOME_OF_CURRENT_READING = """
        SELECT r.outcome FROM fact_extraction_runs AS r
        JOIN sources AS s ON s.id = r.sourceId AND s.currentOcrRevisionId = r.ocrRevisionId
        WHERE r.sourceId = :sourceId ORDER BY r.finishedAt DESC, r.startedAt DESC, r.id DESC LIMIT 1
        """

    const val SOURCE_STATE = "SELECT state FROM sources WHERE id = :sourceId"

    /** P3-05-R1: the reading the source currently shows; evidence on any other reading is not navigable. */
    const val CURRENT_READING_OF_SOURCE = "SELECT currentOcrRevisionId FROM sources WHERE id = :sourceId"

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

    /** C2 §7.4: the run row is written last, in the commit transaction (C2-R32). */
    const val INSERT_RUN = """
        INSERT INTO fact_extraction_runs(id, sourceId, ocrRevisionId, `trigger`, rulesVersion, startedAt, finishedAt,
            outcome, skippedKinds, candidateCount, createdRevisionCount, skippedLineCount)
        VALUES (:id, :sourceId, :ocrRevisionId, :trigger, :rulesVersion, :startedAt, :finishedAt, :outcome,
            :skippedKinds, :candidateCount, :createdRevisionCount, :skippedLineCount)
        """

    const val SOURCE_EPOCH = "SELECT factInputEpoch FROM sources WHERE id = :sourceId"

    /** The current reading of the Source (C2-R14 pointer), with its whole-reading correction, if any (C2-R15). */
    const val READING_OF_SOURCE = """
        SELECT r.id AS id, r.state AS state, r.reviewState AS reviewState,
            EXISTS (
                SELECT 1 FROM ocr_user_revisions u WHERE u.revisionId = r.id AND u.spanId IS NULL
                AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions n WHERE n.supersedesId = u.id)
            ) AS wholeCorrected
        FROM sources s JOIN ocr_revisions r ON r.id = s.currentOcrRevisionId
        WHERE s.id = :sourceId
        """

    /**
     * The spans of [revisionId] with the stored evidence fields, the latest span review, and the head of the
     * correction chain (C2-R15): the chain head is the one no other correction supersedes, never the latest
     * createdAt (VS-3).
     */
    const val SPAN_ROWS_OF_READING = """
        SELECT s.id AS spanId, s.ordinal AS ordinal, s.text AS text, s.hasEvidenceRegion AS hasEvidenceRegion,
            s.scriptStatus AS scriptStatus, s.`left` AS `left`, s.top AS top, s.`right` AS `right`,
            s.bottom AS bottom,
            (SELECT v.reviewState FROM ocr_span_reviews v WHERE v.spanId = s.id ORDER BY v.sequence DESC LIMIT 1)
                AS latestReview,
            (SELECT u.id FROM ocr_user_revisions u WHERE u.spanId = s.id
                AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions n WHERE n.supersedesId = u.id) LIMIT 1)
                AS correctionId,
            (SELECT u.correctedText FROM ocr_user_revisions u WHERE u.spanId = s.id
                AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions n WHERE n.supersedesId = u.id) LIMIT 1)
                AS correctionText
        FROM ocr_spans s WHERE s.revisionId = :revisionId ORDER BY s.ordinal ASC
        """

    /** Every revision of the Source's facts with its single VALUE evidence row, for idempotency and matching. */
    const val REVISION_HISTORY_OF_SOURCE = """
        SELECT r.id AS revisionId, r.factId AS factId, f.ordinal AS factOrdinal, r.kind AS kind,
            r.valueJson AS valueJson, r.canonicalText AS canonicalText, r.origin AS origin, r.status AS status,
            r.supersedesRevisionId AS supersedesRevisionId, (f.currentRevisionId = r.id) AS isCurrent,
            e.ocrSpanId AS ocrSpanId, e.ocrUserRevisionId AS ocrUserRevisionId, e.textStart AS textStart,
            e.regionLeft AS regionLeft, e.regionTop AS regionTop, e.regionRight AS regionRight,
            e.regionBottom AS regionBottom
        FROM facts f JOIN fact_revisions r ON r.factId = f.id
            LEFT JOIN fact_evidence e ON e.revisionId = r.id AND e.role = 'VALUE'
        WHERE f.sourceId = :sourceId
        """

    const val FACT_POINTERS =
        "SELECT id AS factId, currentRevisionId AS currentRevisionId FROM facts WHERE sourceId = :sourceId ORDER BY id"

    const val RUN_COUNT_FOR_READING = "SELECT COUNT(*) FROM fact_extraction_runs WHERE ocrRevisionId = :ocrRevisionId"

    const val EXTRACTED_COUNT_OF_SOURCE = """
        SELECT COUNT(*) FROM fact_revisions r JOIN facts f ON f.id = r.factId
        WHERE f.sourceId = :sourceId AND r.origin = 'EXTRACTED'
        """
}

/** The current reading of a Source (C2-R30, C2-R31). */
data class ReadingRow(val id: String, val state: String, val reviewState: String, val wholeCorrected: Boolean)

/** One span of the current reading, as stored (C2 §1, §4.7). */
data class SpanRow(
    val spanId: String,
    val ordinal: Int,
    val text: String,
    val hasEvidenceRegion: Boolean,
    val scriptStatus: String,
    val left: Int?,
    val top: Int?,
    val right: Int?,
    val bottom: Int?,
    val latestReview: String?,
    val correctionId: String?,
    val correctionText: String?,
)

/** One revision of a fact of the Source, with its VALUE evidence row when it has one. */
data class HistoryRow(
    val revisionId: String,
    val factId: String,
    val factOrdinal: Int,
    val kind: String,
    val valueJson: String,
    val canonicalText: String,
    val origin: String,
    val status: String,
    val supersedesRevisionId: String?,
    val isCurrent: Boolean,
    val ocrSpanId: String?,
    val ocrUserRevisionId: String?,
    val textStart: Int?,
    val regionLeft: Int?,
    val regionTop: Int?,
    val regionRight: Int?,
    val regionBottom: Int?,
)

/** A fact and its current revision id, for the commit's fingerprint (C2-R31). */
data class FactPointer(val factId: String, val currentRevisionId: String?)

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

    @Query(FactSql.EVIDENCE_OF_REVISION)
    suspend fun evidenceOfRevision(revisionId: String): List<FactEvidenceEntity>

    @Query(FactSql.LATEST_DECISION)
    suspend fun latestDecision(revisionId: String): String?

    @Query(FactSql.EXTRACTION_OUTCOME_OF_CURRENT_READING)
    suspend fun extractionOutcomeOfCurrentReading(sourceId: String): String?

    @Query(FactSql.SOURCE_STATE)
    suspend fun sourceState(sourceId: String): String?

    @Query(FactSql.CURRENT_READING_OF_SOURCE)
    suspend fun currentReadingId(sourceId: String): String?

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

    @Suppress("LongParameterList") // one column per parameter
    @Query(FactSql.INSERT_RUN)
    suspend fun insertRun(
        id: String,
        sourceId: String,
        ocrRevisionId: String,
        trigger: String,
        rulesVersion: String,
        startedAt: Long,
        finishedAt: Long,
        outcome: String,
        skippedKinds: String?,
        candidateCount: Int?,
        createdRevisionCount: Int?,
        skippedLineCount: Int?,
    )

    @Query(FactSql.SOURCE_EPOCH)
    suspend fun sourceEpoch(sourceId: String): Long?

    @Query(FactSql.READING_OF_SOURCE)
    suspend fun readingOfSource(sourceId: String): ReadingRow?

    @Query(FactSql.SPAN_ROWS_OF_READING)
    suspend fun spanRowsOfReading(revisionId: String): List<SpanRow>

    @Query(FactSql.REVISION_HISTORY_OF_SOURCE)
    suspend fun revisionHistoryOfSource(sourceId: String): List<HistoryRow>

    @Query(FactSql.FACT_POINTERS)
    suspend fun factPointers(sourceId: String): List<FactPointer>

    @Query(FactSql.RUN_COUNT_FOR_READING)
    suspend fun runCountForReading(ocrRevisionId: String): Int

    @Query(FactSql.EXTRACTED_COUNT_OF_SOURCE)
    suspend fun extractedCountOfSource(sourceId: String): Int
}
