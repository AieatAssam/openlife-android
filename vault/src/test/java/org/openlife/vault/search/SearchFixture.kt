package org.openlife.vault.search

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SearchDao
import org.openlife.vault.storage.SearchRow
import java.io.File
import java.lang.reflect.Proxy

/** Synthetic vault rows on a [HostSqlite] database (AGENTS.md: fixtures are generated, never real). */
class SearchFixture(val host: HostSqlite) {
    private var clock = 1_000L

    fun source(id: String, state: String = "READY") {
        if (state == "READY") {
            host.exec(
                "INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height, " +
                    "orientation, wrappedDek, artefactVersion) " +
                    "VALUES (?, 'READY', ?, 'SHARE', 'image/png', 10, X'01', " +
                    "10, 10, 'NORMAL', X'02', 1)",
                id,
                clock++,
            )
        } else {
            host.exec(
                "INSERT INTO sources(id, state, importedAt, intakeKind) VALUES (?, ?, ?, 'SHARE')",
                id,
                state,
                clock++,
            )
        }
    }

    fun revision(id: String, sourceId: String, state: String = "READY", reviewState: String = "UNREVIEWED") {
        host.exec(
            "INSERT INTO ocr_revisions(id, sourceId, state, engineId, modelVersion, orientation, sourceDigest, " +
                "startedAt, extractedAt, reviewState, failureReason, charCount, spanCount) " +
                "VALUES (?, ?, ?, 'test', '1', 'NORMAL', X'01', ?, ?, ?, NULL, 0, 0)",
            id,
            sourceId,
            state,
            clock,
            clock++,
            reviewState,
        )
    }

    fun span(id: String, revisionId: String, ordinal: Int, text: String) {
        host.exec(
            "INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, " +
                "`left`, top, `right`, " +
                "bottom) VALUES (?, ?, ?, ?, NULL, 'SOURCE_PIXELS', 1, 1, 5, 5)",
            id,
            revisionId,
            ordinal,
            text,
        )
    }

    fun correction(id: String, revisionId: String, spanId: String?, text: String) {
        host.exec(
            "INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor) " +
                "VALUES (?, ?, ?, ?, ?, 'USER')",
            id,
            revisionId,
            spanId,
            text,
            clock++,
        )
    }

    fun review(spanId: String, state: String) {
        host.exec(
            "INSERT INTO ocr_span_reviews(id, spanId, reviewState, createdAt, sequence) " +
                "SELECT ?, ?, ?, ?, COALESCE(MAX(sequence), 0) + 1 FROM ocr_span_reviews WHERE spanId = ?",
            "review-$clock",
            spanId,
            state,
            clock++,
            spanId,
        )
    }

    /** Rows left in each FTS5 index's own storage (docsize), which a stale index would keep. */
    fun indexedDocuments(table: String): Long = host.long("SELECT COUNT(*) FROM ${table}_docsize")

    /** Fails if an index disagrees with its content table. */
    fun integrityCheck(table: String) = host.exec("INSERT INTO $table($table) VALUES('integrity-check')")

    companion object {
        val V4: File = File("schemas/org.openlife.vault.storage.OpenLifeDatabase/4.json")
        val V5: File = File("schemas/org.openlife.vault.storage.OpenLifeDatabase/5.json")

        /** A v4 vault migrated to v5 through the shipped MIGRATION_4_5. */
        fun migrated(host: HostSqlite, seed: SearchFixture.() -> Unit = {}): SearchFixture {
            host.createFromExportedSchema(V4)
            val fixture = SearchFixture(host)
            fixture.seed()
            OpenLifeDatabase.MIGRATION_4_5.migrate(host.asSupportDatabase())
            return fixture
        }
    }
}

/** [SearchDao] over the host database: the repository's SQL runs unchanged; only Room is absent. */
class HostSearchDao(private val host: HostSqlite) : SearchDao {
    var lastSql: String? = null
        private set
    var lastArgs: List<Any?> = emptyList()
        private set

    override fun observe(query: SupportSQLiteQuery): Flow<List<SearchRow>> = flow {
        val args = arrayOfNulls<Any?>(query.argCount)
        query.bindTo(recordingProgram(args))
        lastSql = query.sql
        lastArgs = args.toList()
        emit(
            host.query(query.sql, *args).map { row ->
                SearchRow(
                    sourceId = row[0] as String,
                    revisionId = row[1] as String,
                    spanId = row[2] as String?,
                    text = row[3] as String,
                    isCorrection = (row[4] as Long).toInt(),
                    score = (row[5] as Number).toDouble(),
                    ordinal = (row[6] as Long).toInt(),
                )
            },
        )
    }

    override suspend fun countIndexTables(): Int = host.long(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('ocr_text_fts', 'ocr_correction_fts')",
    ).toInt()

    private fun recordingProgram(args: Array<Any?>): SupportSQLiteProgram = Proxy.newProxyInstance(
        SupportSQLiteProgram::class.java.classLoader,
        arrayOf(SupportSQLiteProgram::class.java),
    ) { _, method, params ->
        when (method.name) {
            "bindNull" -> args[(params[0] as Int) - 1] = null
            "bindLong", "bindDouble", "bindString", "bindBlob" -> args[(params[0] as Int) - 1] = params[1]
            "close", "clearBindings" -> Unit
            else -> error("unexpected ${method.name}")
        }
        null
    } as SupportSQLiteProgram
}
