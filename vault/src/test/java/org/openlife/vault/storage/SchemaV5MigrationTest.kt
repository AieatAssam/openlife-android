package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * P2-08-R2 under plan conventions.schema_migrations and P1-18-R12: v4->v5
 * adds only the FTS5 index (virtual tables and triggers created by the
 * migration, not Room entities), so the exported v5 schema equals v4 apart
 * from its version; both copies of 5.json are identical; the migration ends
 * by re-asserting the READY triggers. The SQL itself runs in SearchIndexHostTest.
 */
class SchemaV5MigrationTest {
    private val dir = "org.openlife.vault.storage.OpenLifeDatabase"
    private val v4 = File("schemas/$dir/4.json")
    private val exported = File("schemas/$dir/5.json")
    private val asset = File("src/androidTest/assets/$dir/5.json")

    @Test
    fun bothCopiesOfTheV5SchemaAreByteIdentical() {
        assertTrue("exported schema missing at ${exported.absolutePath}", exported.isFile)
        assertTrue("androidTest asset missing at ${asset.absolutePath}", asset.isFile)
        assertArrayEquals(exported.readBytes(), asset.readBytes())
    }

    @Test
    fun exportedV5DiffersFromV4OnlyInItsVersion() {
        val five = exported.readText()
        assertTrue(five.contains("\"version\": 5,"))
        assertEquals(v4.readText().replace("\"version\": 4,", "\"version\": 5,"), five)
        assertFalse("FTS tables are outside Room's schema", five.contains("fts"))
    }

    @Test
    fun migrationFourToFiveCreatesTheIndexThenReassertsTheReadyTriggers() {
        val executed = record(OpenLifeDatabase.MIGRATION_4_5)
        val index = SearchIndex.statements()
        val triggers = ReadyInvariantTriggers.names
        assertTrue("the migration runs every index statement in order", executed.containsAll(index))
        assertEquals(index, executed.filter { it in index })
        assertTrue(executed.none { it.startsWith("ALTER") || (it.startsWith("DROP TABLE") && !it.contains("temp.")) })
        val tail = executed.takeLast(triggers.size * 2)
        triggers.forEachIndexed { i, name ->
            assertEquals("DROP TRIGGER IF EXISTS $name", tail[i * 2])
            assertTrue("$name is recreated", tail[i * 2 + 1].startsWith("CREATE TRIGGER $name"))
        }
    }

    @Test
    fun indexStatementsUseTheBriefsTablesTokenizerAndTriggers() {
        val sql = SearchIndex.statements()
        listOf(
            SearchIndex.TEXT_TABLE to "content='ocr_spans'",
            SearchIndex.CORRECTION_TABLE to "content='ocr_user_revisions'",
        )
            .forEach { (table, content) ->
                val create = sql.single { it.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS $table ") }
                assertTrue(create, create.contains("USING fts5("))
                assertTrue(create, create.contains(content))
                assertTrue(create, create.contains("tokenize='${SearchIndex.TOKENIZER}'"))
                assertTrue("existing rows are indexed", sql.contains("INSERT INTO $table($table) VALUES('rebuild')"))
                val secureDelete = "INSERT INTO $table($table, rank) VALUES('secure-delete', 1)"
                assertEquals("W-01: secure-delete next", sql.indexOf(create) + 1, sql.indexOf(secureDelete))
            }
        assertEquals(6, SearchIndex.triggerNames.size)
        SearchIndex.triggerNames.forEach { name ->
            assertTrue(name, sql.any { it.startsWith("CREATE TRIGGER $name AFTER") })
        }
        val events = listOf("INSERT", "DELETE")
        assertTrue(events.all { event -> sql.count { it.contains("AFTER $event ON") } == 2 })
        // C2-R64 (P3-02): update triggers fire only on the indexed column.
        assertTrue(sql.any { it.contains("AFTER UPDATE OF text ON ocr_spans") })
        assertTrue(sql.any { it.contains("AFTER UPDATE OF correctedText ON ocr_user_revisions") })
        assertTrue(sql.none { it.contains("AFTER UPDATE ON") })
    }

    @Test
    fun migrationsAreListedInOrderThroughVersionFive() {
        val versions = OpenLifeDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), versions.take(4))
    }

    private fun record(migration: androidx.room.migration.Migration): List<String> {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            require(method.name == "execSQL" && args?.size == 1) { "unexpected call ${method.name}" }
            statements += (args[0] as String).trim()
            null
        } as SupportSQLiteDatabase
        migration.migrate(db)
        return statements
    }
}
