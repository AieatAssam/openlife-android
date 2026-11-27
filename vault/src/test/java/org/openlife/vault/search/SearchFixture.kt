package org.openlife.vault.search

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SearchDao
import org.openlife.vault.storage.SearchIndex
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

    /**
     * Review W-01 oracle: whether [term]'s UTF-8 bytes occur anywhere in the
     * live `<table>_data` segments. FTS5 prefix-compresses terms, so a test
     * asserts presence before the delete (positive control) and uses terms
     * with distinct first letters.
     */
    fun dataContains(table: String, term: String): Boolean {
        // FTS5 prefix-compresses each term against the one before it in the
        // segment ("lamp" then "lighthouse" stores only "ighthouse"), so the
        // scan looks for the term's tail, which is stored whenever the term is.
        val needle = term.drop(if (term.length >= LONG_TERM) 2 else 1).toByteArray(Charsets.UTF_8)
        return host.query("SELECT block FROM ${table}_data").any { row ->
            val block = row.single() as? ByteArray ?: return@any false
            (0..block.size - needle.size).any { start -> needle.indices.all { block[start + it] == needle[it] } }
        }
    }

    /** The persisted FTS5 secure-delete option of [table] (null when unset). */
    fun secureDelete(table: String): Long? =
        host.query("SELECT v FROM ${table}_config WHERE k = 'secure-delete'").singleOrNull()?.single() as Long?

    /**
     * Fails if an index is internally inconsistent or (rank 1, external
     * content) disagrees with its content table. PR #36: SQLite 3.42.0 to
     * 3.46.0 write a corrupt index here, which only this check reveals;
     * queries still answer.
     */
    fun integrityCheck(table: String) {
        host.exec("INSERT INTO $table($table) VALUES('integrity-check')")
        host.exec("INSERT INTO $table($table, rank) VALUES('integrity-check', 1)")
    }

    /** [integrityCheck] on both index tables. */
    fun integrityCheckAll() = SearchIndex.tables.forEach(::integrityCheck)

    companion object {
        private const val LONG_TERM = 6

        val V4: File = File("schemas/org.openlife.vault.storage.OpenLifeDatabase/4.json")
        val V5: File = File("schemas/org.openlife.vault.storage.OpenLifeDatabase/5.json")

        /**
         * A v4 vault migrated to v5 through the shipped MIGRATION_4_5. The
         * index needs SQLite [SearchIndex.MIN_SQLITE_VERSION] (FTS5
         * secure-delete without the 3.42.0-3.46.0 corruption); on an older
         * engine the migration correctly creates no index ([assumeIndexed]).
         */
        fun migrated(host: HostSqlite, seed: SearchFixture.() -> Unit = {}): SearchFixture {
            host.createFromExportedSchema(V4)
            val fixture = SearchFixture(host)
            fixture.seed()
            OpenLifeDatabase.MIGRATION_4_5.migrate(host.asSupportDatabase())
            assumeIndexed(host)
            return fixture
        }

        /**
         * On the pinned engine (ADR-0017) a missing index is a failure, so no
         * search test skips by construction. Only an engine selected with
         * `OPENLIFE_HOST_SQLITE` may skip, when it is older than the floor.
         */
        fun assumeIndexed(host: HostSqlite) {
            val version = host.version()
            val tables = host.long(
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('ocr_text_fts', 'ocr_correction_fts')",
            )
            if (HostSqlite.isPinned) {
                assertEquals("the search index exists on the pinned SQLite $version", 2L, tables)
                return
            }
            assumeTrue(
                "OPENLIFE_HOST_SQLITE engine $version is older than ${SearchIndex.MIN_SQLITE_VERSION}: " +
                    "no index is created (FTS5 secure-delete missing before 3.42, corrupting from 3.42.0 " +
                    "to 3.46.0); test skipped",
                tables == 2L,
            )
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
