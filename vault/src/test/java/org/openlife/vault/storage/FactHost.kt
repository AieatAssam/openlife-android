package org.openlife.vault.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.openlife.vault.search.SearchFixture
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import java.io.File

/**
 * P3-02 host fixtures: the C2 schema on host SQLite (not SQLCipher), reached
 * either by the shipped migrations from an older exported schema or as a
 * fresh database built from 6.json plus the shipped create callback. The DAO
 * implementations below run the production SQL constants unchanged; only
 * Room's binding is replaced (named parameters become positional ones).
 */
object FactHost {
    private const val DIR = "schemas/org.openlife.vault.storage.OpenLifeDatabase"

    fun schema(version: Int): File = File("$DIR/$version.json")

    /** A fresh v6 database, as Room creates it: 6.json DDL, then the shipped onCreate callback. */
    fun fresh(host: HostSqlite): SearchFixture {
        host.createFromExportedSchema(schema(6))
        OpenLifeDatabase.readyInvariantCallback.onCreate(host.asSupportDatabase())
        return SearchFixture(host)
    }

    /** A v[from] database seeded by [seed] and migrated to v6 through every shipped migration. */
    fun migrated(host: HostSqlite, from: Int = 5, seed: SearchFixture.() -> Unit = {}): SearchFixture {
        host.createFromExportedSchema(schema(from))
        val fixture = SearchFixture(host)
        fixture.seed()
        val db = host.asSupportDatabase()
        OpenLifeDatabase.MIGRATIONS.filter { it.startVersion >= from }.forEach { it.migrate(db) }
        return fixture
    }

    private val NAMED = Regex(":([A-Za-z_][A-Za-z0-9_]*)")

    /** Runs [sql] with Room-style `:name` parameters; a List parameter expands as Room expands it. */
    fun HostSqlite.named(sql: String, vararg params: Pair<String, Any?>): List<List<Any?>> {
        val values = params.toMap()
        val args = mutableListOf<Any?>()
        val positional = NAMED.replace(sql) { match ->
            val value = values.getValue(match.groupValues[1])
            if (value is List<*>) {
                args.addAll(value)
                value.joinToString(", ") { "?" }
            } else {
                args.add(value)
                "?"
            }
        }
        return query(positional, *args.toTypedArray())
    }

    /** Number of rows changed by the last statement on this connection. */
    fun HostSqlite.changes(): Int = (query("SELECT changes()").single().single() as Long).toInt()
}

/** Inserts the rows a test needs, through the production SQL where one exists. */
class FactRows(val host: HostSqlite) {
    private var clock = 10_000L

    fun fact(id: String, sourceId: String, kind: String = "DATE") {
        with(
            FactHost,
        ) {
            host.named(
                FactSql.INSERT_FACT,
                "id" to id,
                "sourceId" to sourceId,
                "kind" to kind,
                "createdAt" to clock++,
            )
        }
    }

    @Suppress("LongParameterList") // one column per parameter
    fun revision(
        id: String,
        factId: String,
        origin: String = "EXTRACTED",
        status: String = "UNREVIEWED",
        kind: String = "DATE",
        supersedes: String? = null,
        valueJson: String = "{\"date\":\"2026-11-26\"}",
        canonicalText: String = "2026-11-26",
    ) {
        val user = origin == "USER"
        with(FactHost) {
            host.named(
                FactSql.INSERT_REVISION,
                "id" to id,
                "factId" to factId,
                "origin" to origin,
                "kind" to kind,
                "valueJson" to valueJson,
                "canonicalText" to canonicalText,
                "rawText" to if (user) null else "26.11.2026",
                "status" to status,
                "methodId" to if (user) "user:correct" else "date:numeric",
                "methodVersion" to if (user) null else "1",
                "locale" to if (user) null else "en-GB",
                "createdAt" to clock++,
                "supersedesRevisionId" to supersedes,
            )
        }
    }

    fun current(factId: String, revisionId: String, expected: String? = null): Int = with(FactHost) {
        host.named(
            FactSql.MOVE_CURRENT_REVISION,
            "factId" to factId,
            "expectedRevisionId" to expected,
            "revisionId" to revisionId,
        )
        host.changes()
    }

    @Suppress("LongParameterList") // one column per parameter
    fun evidence(
        id: String,
        revisionId: String,
        sourceId: String,
        spanId: String,
        role: String = "VALUE",
        start: Int? = 0,
        end: Int? = 4,
    ) {
        with(FactHost) {
            host.named(
                FactSql.INSERT_EVIDENCE,
                "id" to id,
                "revisionId" to revisionId,
                "role" to role,
                "sourceId" to sourceId,
                "ocrSpanId" to spanId,
                "ocrUserRevisionId" to null,
                "textStart" to start,
                "textEnd" to end,
            )
        }
    }

    fun reviewEvent(id: String, revisionId: String, decision: String = "CONFIRM") {
        with(FactHost) {
            host.named(
                FactSql.APPEND_REVIEW_EVENT,
                "id" to id,
                "revisionId" to revisionId,
                "decision" to decision,
                "createdAt" to clock++,
            )
        }
    }

    fun run(id: String, sourceId: String, ocrRevisionId: String) {
        host.exec(
            "INSERT INTO fact_extraction_runs(id, sourceId, ocrRevisionId, `trigger`, rulesVersion, startedAt, " +
                "finishedAt, outcome, skippedKinds, candidateCount, createdRevisionCount, skippedLineCount) " +
                "VALUES (?, ?, ?, 'AUTO', '1', ?, ?, 'COMPLETED', NULL, 1, 1, 0)",
            id,
            sourceId,
            ocrRevisionId,
            clock,
            clock++,
        )
    }

    /** The head-naming correction insert a repository runs (C2-R15). */
    fun correction(id: String, revisionId: String, spanId: String?, text: String, supersedes: String?) {
        host.exec(
            "INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor, supersedesId) " +
                "VALUES (?, ?, ?, ?, ?, 'USER', ?)",
            id,
            revisionId,
            spanId,
            text,
            clock++,
            supersedes,
        )
    }

    /** Moves the current-reading pointer as the READY commit does (C2-R14); returns rows changed. */
    fun moveCurrentReading(sourceId: String, revisionId: String): Int = with(FactHost) {
        host.named(OcrPointerSql.MOVE_CURRENT_READING, "sourceId" to sourceId, "revisionId" to revisionId)
        host.changes()
    }

    fun setState(sourceId: String, state: String) = host.exec(
        "UPDATE sources SET state = ? WHERE id = ?",
        state,
        sourceId,
    )

    /** A READY source with one READY reading `<s>-r` that is current and spans `<s>-s1`, `<s>-s2`. */
    fun readySourceWithReading(fixture: SearchFixture, sourceId: String) {
        fixture.source(sourceId)
        fixture.revision("$sourceId-r", sourceId)
        fixture.span("$sourceId-s1", "$sourceId-r", 0, "Due 26.11.2026")
        fixture.span("$sourceId-s2", "$sourceId-r", 1, "Total EUR 12.50")
        moveCurrentReading(sourceId, "$sourceId-r")
    }

    /** Every row of [table], ordered, for whole-database comparisons. */
    fun dump(table: String): List<List<Any?>> = host.query("SELECT * FROM $table ORDER BY 1")
}

/** [FactDao] over the host database: FactSql runs unchanged. */
@Suppress("TooManyFunctions") // mirrors FactDao
class HostFactDao(private val host: HostSqlite) : FactDao {
    private fun q(sql: String, vararg params: Pair<String, Any?>) = with(FactHost) { host.named(sql, *params) }

    /** A write: the number of rows it changed. */
    private fun count(sql: String, vararg params: Pair<String, Any?>): Int = with(FactHost) {
        q(sql, *params)
        host.changes()
    }

    /** A SELECT COUNT(*): its result, not the changes() of the last write on the connection. */
    private fun selectCount(sql: String, vararg params: Pair<String, Any?>): Int =
        (q(sql, *params).single().single() as Long).toInt()

    override suspend fun insertFact(id: String, sourceId: String, kind: String, createdAt: Long) {
        q(FactSql.INSERT_FACT, "id" to id, "sourceId" to sourceId, "kind" to kind, "createdAt" to createdAt)
    }

    override suspend fun insertRevision(
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
    ) {
        q(
            FactSql.INSERT_REVISION, "id" to id, "factId" to factId, "origin" to origin, "kind" to kind,
            "valueJson" to valueJson, "canonicalText" to canonicalText, "rawText" to rawText, "status" to status,
            "methodId" to methodId, "methodVersion" to methodVersion, "locale" to locale, "createdAt" to createdAt,
            "supersedesRevisionId" to supersedesRevisionId,
        )
    }

    override suspend fun insertEvidence(
        id: String,
        revisionId: String,
        role: String,
        sourceId: String,
        ocrSpanId: String,
        ocrUserRevisionId: String?,
        textStart: Int?,
        textEnd: Int?,
    ) {
        q(
            FactSql.INSERT_EVIDENCE, "id" to id, "revisionId" to revisionId, "role" to role, "sourceId" to sourceId,
            "ocrSpanId" to ocrSpanId, "ocrUserRevisionId" to ocrUserRevisionId, "textStart" to textStart,
            "textEnd" to textEnd,
        )
    }

    override suspend fun appendReviewEvent(id: String, revisionId: String, decision: String, createdAt: Long) {
        q(
            FactSql.APPEND_REVIEW_EVENT,
            "id" to id,
            "revisionId" to revisionId,
            "decision" to decision,
            "createdAt" to createdAt,
        )
    }

    override suspend fun moveCurrentRevision(factId: String, expectedRevisionId: String?, revisionId: String): Int =
        count(
            FactSql.MOVE_CURRENT_REVISION,
            "factId" to factId,
            "expectedRevisionId" to expectedRevisionId,
            "revisionId" to revisionId,
        )

    override suspend fun setStatus(id: String, from: String, to: String): Int =
        count(FactSql.SET_STATUS, "id" to id, "from" to from, "to" to to)

    override suspend fun markStale(ids: List<String>, reason: String, staleAt: Long): Int =
        count(FactSql.MARK_STALE, "ids" to ids, "reason" to reason, "staleAt" to staleAt)

    override suspend fun bumpFactInputEpoch(sourceId: String): Int =
        count(FactSql.BUMP_FACT_INPUT_EPOCH, "sourceId" to sourceId)

    override suspend fun findFact(id: String): FactEntity? = q(FactSql.FIND_FACT, "id" to id).singleOrNull()?.let {
        FactEntity(
            it[0] as String,
            it[1] as String,
            it[2] as String,
            (it[3] as Long).toInt(),
            it[4] as String?,
            it[5] as Long,
        )
    }

    override suspend fun findRevision(id: String): FactRevisionEntity? =
        q(FactSql.FIND_REVISION, "id" to id).singleOrNull()?.let(::revisionOf)

    override suspend fun sourceState(sourceId: String): String? =
        q(FactSql.SOURCE_STATE, "sourceId" to sourceId).singleOrNull()?.single() as String?

    override suspend fun countCurrentRevisions(sourceId: String): Int =
        (q(FactSql.COUNT_CURRENT_REVISIONS, "sourceId" to sourceId).single().single() as Long).toInt()

    override suspend fun countValueEvidence(revisionId: String): Int =
        (q(FactSql.COUNT_VALUE_EVIDENCE, "revisionId" to revisionId).single().single() as Long).toInt()

    override suspend fun basisOf(revisionId: String): String? =
        q(FactSql.BASIS_OF, "revisionId" to revisionId).singleOrNull()?.single() as String?

    override suspend fun dependentsOfSpan(spanId: String): List<DependencyNode> =
        q(FactSql.DEPENDENTS_OF_SPAN, "spanId" to spanId).map(::nodeOf)

    override suspend fun dependentsOfReading(ocrRevisionId: String): List<DependencyNode> =
        q(FactSql.DEPENDENTS_OF_READING, "ocrRevisionId" to ocrRevisionId).map(::nodeOf)

    override suspend fun dependentsOf(kind: String, ids: List<String>): List<DependencyNode> =
        q(FactSql.DEPENDENTS_OF, "kind" to kind, "ids" to ids).map(::nodeOf)

    override suspend fun sourceOfSpan(spanId: String): String? =
        q(FactSql.SOURCE_OF_SPAN, "spanId" to spanId).singleOrNull()?.single() as String?

    override suspend fun sourceOfReading(ocrRevisionId: String): String? =
        q(FactSql.SOURCE_OF_READING, "ocrRevisionId" to ocrRevisionId).singleOrNull()?.single() as String?

    override fun observeCurrentFacts(sourceId: String): Flow<List<CurrentFactRow>> = flow {
        emit(
            q(FactSql.OBSERVE_CURRENT_FACTS, "sourceId" to sourceId).map { row ->
                CurrentFactRow(
                    revisionOf(row.take(REVISION_COLUMNS)),
                    row[REVISION_COLUMNS] as String,
                    (row[REVISION_COLUMNS + 1] as Long).toInt(),
                    row[REVISION_COLUMNS + 2] as Long,
                    row[REVISION_COLUMNS + 3] as String,
                )
            },
        )
    }

    @Suppress("LongParameterList") // one column per parameter
    override suspend fun insertRun(
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
    ) {
        q(
            FactSql.INSERT_RUN,
            "id" to id,
            "sourceId" to sourceId,
            "ocrRevisionId" to ocrRevisionId,
            "trigger" to trigger,
            "rulesVersion" to rulesVersion,
            "startedAt" to startedAt,
            "finishedAt" to finishedAt,
            "outcome" to outcome,
            "skippedKinds" to skippedKinds,
            "candidateCount" to candidateCount,
            "createdRevisionCount" to createdRevisionCount,
            "skippedLineCount" to skippedLineCount,
        )
    }

    override suspend fun sourceEpoch(sourceId: String): Long? =
        q(FactSql.SOURCE_EPOCH, "sourceId" to sourceId).singleOrNull()?.single() as Long?

    override suspend fun readingOfSource(sourceId: String): ReadingRow? =
        q(FactSql.READING_OF_SOURCE, "sourceId" to sourceId).singleOrNull()?.let { r ->
            ReadingRow(r[0] as String, r[1] as String, r[2] as String, (r[3] as Long) != 0L)
        }

    override suspend fun spanRowsOfReading(revisionId: String): List<SpanRow> =
        q(FactSql.SPAN_ROWS_OF_READING, "revisionId" to revisionId).map { r ->
            SpanRow(
                spanId = r[0] as String,
                ordinal = (r[1] as Long).toInt(),
                text = r[2] as String,
                hasEvidenceRegion = (r[3] as Long) != 0L,
                scriptStatus = r[4] as String,
                left = (r[5] as Long?)?.toInt(),
                top = (r[6] as Long?)?.toInt(),
                right = (r[7] as Long?)?.toInt(),
                bottom = (r[8] as Long?)?.toInt(),
                latestReview = r[9] as String?,
                correctionId = r[10] as String?,
                correctionText = r[11] as String?,
            )
        }

    override suspend fun revisionHistoryOfSource(sourceId: String): List<HistoryRow> =
        q(FactSql.REVISION_HISTORY_OF_SOURCE, "sourceId" to sourceId).map { r ->
            HistoryRow(
                revisionId = r[0] as String,
                factId = r[1] as String,
                factOrdinal = (r[2] as Long).toInt(),
                kind = r[3] as String,
                valueJson = r[4] as String,
                canonicalText = r[5] as String,
                origin = r[6] as String,
                status = r[7] as String,
                supersedesRevisionId = r[8] as String?,
                isCurrent = (r[9] as Long) != 0L,
                ocrSpanId = r[10] as String?,
                ocrUserRevisionId = r[11] as String?,
                textStart = (r[12] as Long?)?.toInt(),
                regionLeft = (r[13] as Long?)?.toInt(),
                regionTop = (r[14] as Long?)?.toInt(),
                regionRight = (r[15] as Long?)?.toInt(),
                regionBottom = (r[16] as Long?)?.toInt(),
            )
        }

    override suspend fun factPointers(sourceId: String): List<FactPointer> =
        q(FactSql.FACT_POINTERS, "sourceId" to sourceId).map { FactPointer(it[0] as String, it[1] as String?) }

    override suspend fun runCountForReading(ocrRevisionId: String): Int =
        selectCount(FactSql.RUN_COUNT_FOR_READING, "ocrRevisionId" to ocrRevisionId)

    override suspend fun extractedCountOfSource(sourceId: String): Int =
        selectCount(FactSql.EXTRACTED_COUNT_OF_SOURCE, "sourceId" to sourceId)

    private fun nodeOf(row: List<Any?>) = DependencyNode(row[0] as String, row[1] as String)

    private fun revisionOf(r: List<Any?>) = FactRevisionEntity(
        id = r[0] as String,
        factId = r[1] as String,
        revisionNo = (r[2] as Long).toInt(),
        origin = r[3] as String,
        kind = r[4] as String,
        valueJson = r[5] as String,
        canonicalText = r[6] as String,
        rawText = r[7] as String?,
        status = r[8] as String,
        staleReason = r[9] as String?,
        staleAt = r[10] as Long?,
        confidence = r[11] as Double?,
        methodId = r[12] as String,
        methodVersion = r[13] as String?,
        locale = r[14] as String?,
        createdAt = r[15] as Long,
        supersedesRevisionId = r[16] as String?,
    )

    private companion object {
        const val REVISION_COLUMNS = 17
    }
}

/** [SourceRowRemovalDao] over the host database. */
class HostSourceRowRemovalDao(private val host: HostSqlite) : SourceRowRemovalDao {
    val executed = mutableListOf<String>()

    private fun run(sql: String, sourceId: String) {
        executed += sql
        with(FactHost) { host.named(sql, "sourceId" to sourceId) }
    }

    override suspend fun sourceState(sourceId: String): String? = with(
        FactHost,
    ) { host.named(SourceRowRemoval.SOURCE_STATE, "sourceId" to sourceId) }.singleOrNull()?.single() as String?

    override suspend fun deleteReviewEvents(sourceId: String) = run(SourceRowRemoval.DELETE_REVIEW_EVENTS, sourceId)

    override suspend fun deleteFactEvidence(sourceId: String) = run(SourceRowRemoval.DELETE_FACT_EVIDENCE, sourceId)

    override suspend fun deleteFactRevisions(sourceId: String) = run(SourceRowRemoval.DELETE_FACT_REVISIONS, sourceId)

    override suspend fun deleteFacts(sourceId: String) = run(SourceRowRemoval.DELETE_FACTS, sourceId)

    override suspend fun deleteExtractionRuns(sourceId: String) = run(SourceRowRemoval.DELETE_EXTRACTION_RUNS, sourceId)

    override suspend fun deleteSpanReviews(sourceId: String) = run(SourceRowRemoval.DELETE_SPAN_REVIEWS, sourceId)

    override suspend fun deleteCorrections(sourceId: String) = run(SourceRowRemoval.DELETE_CORRECTIONS, sourceId)

    override suspend fun deleteSpans(sourceId: String) = run(SourceRowRemoval.DELETE_SPANS, sourceId)

    override suspend fun deleteReadings(sourceId: String) = run(SourceRowRemoval.DELETE_READINGS, sourceId)

    /** Step 7, as SourceDao.deleteById runs it. */
    suspend fun deleteSource(id: String) {
        executed += SourceRowRemoval.DELETE_SOURCE
        with(FactHost) { host.named(SourceRowRemoval.DELETE_SOURCE, "id" to id) }
    }
}
