package org.openlife.vault.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-07-R1 on SQLCipher through Room: a v6 vault holding a fact migrates to v7
 * with the fact index (fact_fts) created, its triggers present, secure-delete
 * persisted, and the pre-existing revision backfilled (findable). A fresh v7
 * create has the same index. Device gap until run on the tablet.
 */
@RunWith(AndroidJUnit4::class)
class FactSearchMigrationSqlCipherTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @Before
    fun loadSqlCipher() = System.loadLibrary("sqlcipher")

    @After
    fun deleteDatabases() = names.forEach(context::deleteDatabase)

    private fun uniqueName(prefix: String): String = "$prefix-${UUID.randomUUID()}".also(names::add)

    @Test
    fun versionSixVaultWithAFactMigratesToAnIndexedV7OnSqlCipher() {
        val name = uniqueName("p307-migration")
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(ByteArray(32) { 7 }),
        )
        val v6 = helper.createDatabase(name, 6)
        // A v6 vault as it shipped carries the OCR index; the helper does not create it.
        assertTrue("OCR FTS5 index on SQLCipher", SearchIndex.ensure(v6))
        FactDeviceRows(v6).apply {
            readySourceWithReading("s1")
            extractedFact("s1", "f1", "fr1")
        }
        v6.close()
        val migrated = helper.runMigrationsAndValidate(name, 7, false, *OpenLifeDatabase.MIGRATIONS.toTypedArray())
        try {
            NonEntityTables.assertOnlySearchIndex(migrated, 7)
            val rows = FactDeviceRows(migrated)
            assertEquals("the fact survives the migration", 1, rows.count("facts"))
            assertEquals(
                "both fact index triggers exist",
                "2",
                rows.text(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                        "AND name IN ('fact_fts_ai', 'fact_fts_ad')",
                ),
            )
            assertEquals("secure-delete is persisted", "1", rows.text("SELECT v FROM fact_fts_config WHERE k = 'secure-delete'"))
            assertEquals(
                "the pre-existing revision is backfilled into the index",
                "1",
                rows.text("SELECT COUNT(*) FROM fact_fts WHERE fact_fts MATCH '2026'"),
            )
            migrated.execSQL("INSERT INTO fact_fts(fact_fts) VALUES('integrity-check')")
        } finally {
            migrated.close()
        }
    }

    @Test
    fun freshVersionSevenVaultHasAnIntactFactIndexOnSqlCipher() {
        val name = uniqueName("p307-fresh")
        val db = OpenLifeDatabaseFactory.create(
            context = context,
            paths = VaultPaths(context),
            databaseSecret = ByteArray(32) { 9 },
            databasePath = context.getDatabasePath(name).absolutePath,
        )
        try {
            val sql = db.openHelper.writableDatabase
            val rows = FactDeviceRows(sql)
            assertEquals(
                "both fact index triggers exist",
                "2",
                rows.text(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' " +
                        "AND name IN ('fact_fts_ai', 'fact_fts_ad')",
                ),
            )
            assertEquals("secure-delete is persisted", "1", rows.text("SELECT v FROM fact_fts_config WHERE k = 'secure-delete'"))
            sql.execSQL("INSERT INTO fact_fts(fact_fts) VALUES('integrity-check')")
        } finally {
            db.close()
        }
    }
}
