package org.openlife.vault.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.openlife.vault.storage.HostSqlite
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import org.openlife.vault.storage.OpenLifeDatabase
import org.openlife.vault.storage.SearchIndex
import org.openlife.vault.storage.SqliteVersionFloorTest

/**
 * ADR-0017: the vault host tests run on a pinned SQLite (sqlite-jdbc, a
 * test-only dependency) whose version is the SQLite inside the shipped
 * SQLCipher, so the search-index tests run on every host and never skip.
 * A SQLCipher bump without a matching sqlite-jdbc bump fails here.
 */
class PinnedEngineTest {
    /** Not applicable when `OPENLIFE_HOST_SQLITE` selects another engine on purpose. */
    @Before
    fun onlyOnThePinnedEngine() {
        assumeTrue("OPENLIFE_HOST_SQLITE selects another engine", HostSqlite.isPinned)
    }

    @Test
    fun theDefaultEngineIsTheSqliteInsideTheShippedSqlcipher() {
        HostSqlite().use { host ->
            assertEquals(SqliteVersionFloorTest.BUNDLED_SQLITE.toString(), host.version())
        }
    }

    @Test
    fun theSearchIndexIsCreatedOnTheDefaultEngine() {
        HostSqlite().use { host ->
            host.createFromV4AndMigrate()
            assertTrue(
                "SQLite ${host.version()} is an engine the index is created on",
                SearchIndex.isSupportedEngine(host.asSupportDatabase()),
            )
            assertEquals(
                2L,
                host.long("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('ocr_text_fts', 'ocr_correction_fts')"),
            )
        }
    }

    private fun HostSqlite.createFromV4AndMigrate() {
        createFromExportedSchema(SearchFixture.V4)
        OpenLifeDatabase.MIGRATION_4_5.migrate(asSupportDatabase())
    }
}
