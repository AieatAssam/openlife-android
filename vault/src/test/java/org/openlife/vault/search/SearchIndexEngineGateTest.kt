package org.openlife.vault.search

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import org.openlife.vault.storage.SearchIndex
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * PR #36 CI finding: SQLite 3.42.0 to 3.46.0 write a corrupt FTS5
 * external-content index when a row is removed with the 'delete' command and
 * secure-delete is on (host reproduction: one insert and its delete fail
 * `integrity-check`; an index written by 3.46.1 or later passes it on every
 * engine). The index is therefore never created, and never kept at open, on
 * an engine older than [SearchIndex.MIN_SQLITE_VERSION]; search falls back
 * to LIKE (fail closed). The engine version is presented through
 * `sqlite_version()`, so this runs on any host SQLite.
 */
class SearchIndexEngineGateTest {
    private val host = HostSqlite()

    @After
    fun tearDown() = host.close()

    /** [host] as a SupportSQLiteDatabase whose `SELECT sqlite_version()` reports [version]. */
    private fun reporting(version: String): SupportSQLiteDatabase {
        val real = host.asSupportDatabase()
        val literal = "SELECT '" + version.replace("'", "''") + "'"
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            val sql = args?.singleOrNull() as? String
            val asksVersion = method.name == "query" && sql?.contains("sqlite_version()", ignoreCase = true) == true
            try {
                if (asksVersion) real.query(literal) else method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as SupportSQLiteDatabase
    }

    private fun ftsTables(): Long = host.long(
        "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('ocr_text_fts', 'ocr_correction_fts')",
    )

    private fun v5() = host.createFromExportedSchema(SearchFixture.V5)

    @Test
    fun theMinimumIsTheFirstEngineWithoutTheCorruption() {
        assertEquals("3.46.1", SearchIndex.MIN_SQLITE_VERSION)
    }

    @Test
    fun noIndexIsCreatedOnAnAffectedEngine() {
        v5()
        listOf("3.42.0", "3.45.1", "3.46.0").forEach { version ->
            assertFalse("create on $version", SearchIndex.create(reporting(version)))
            assertEquals("no FTS table on $version", 0L, ftsTables())
        }
    }

    @Test
    fun anExistingIndexIsDroppedAtOpenOnAnAffectedEngineAndKeptOtherwise() {
        v5()
        assertTrue("index on the host engine", SearchIndex.create(host.asSupportDatabase()))
        assertTrue("kept on 3.46.1", SearchIndex.ensure(reporting("3.46.1")))
        assertEquals(2L, ftsTables())
        assertTrue("kept on 3.53.4", SearchIndex.ensure(reporting("3.53.4")))
        assertFalse("dropped on 3.45.1", SearchIndex.ensure(reporting("3.45.1")))
        assertEquals("fail closed: no index left", 0L, ftsTables())
    }

    @Test
    fun anUnreadableVersionFailsClosed() {
        v5()
        listOf("", "garbage", "3", "3.x.1").forEach { version ->
            assertFalse("create with version '$version'", SearchIndex.create(reporting(version)))
        }
        assertEquals(0L, ftsTables())
    }
}
