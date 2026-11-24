package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * P2-06-R12 / E-05 and P1-18-R12: the v2->v3 migration creates exactly the
 * `ocr_span_reviews` table and indices Room exported for v3, both copies of
 * 3.json are identical, and the migration ends by re-asserting the READY
 * invariant triggers. Room's own validation runs only on a device
 * (OcrDatabaseTest); this proves the same DDL on the JVM.
 */
class SchemaV3MigrationTest {
    private val exported = File("schemas/org.openlife.vault.storage.OpenLifeDatabase/3.json")
    private val asset = File("src/androidTest/assets/org.openlife.vault.storage.OpenLifeDatabase/3.json")

    @Test
    fun bothCopiesOfTheV3SchemaAreByteIdentical() {
        assertTrue("exported schema missing at ${exported.absolutePath}", exported.isFile)
        assertTrue("androidTest asset missing at ${asset.absolutePath}", asset.isFile)
        assertArrayEquals(exported.readBytes(), asset.readBytes())
    }

    @Test
    fun migrationTwoToThreeCreatesWhatRoomExportedForSpanReviews() {
        val executed = record(OpenLifeDatabase.MIGRATION_2_3)
        val expected = exportedCreateSql("ocr_span_reviews")
        assertTrue(
            "the exported table must carry a sequence column: $expected",
            expected.first().contains("`sequence`"),
        )
        assertEquals(expected, executed.filterNot { it.contains("TRIGGER") })
    }

    @Test
    fun migrationTwoToThreeEndsByReassertingTheReadyTriggers() {
        val executed = record(OpenLifeDatabase.MIGRATION_2_3)
        val triggers = ReadyInvariantTriggers.names
        val tail = executed.takeLast(triggers.size * 2)
        triggers.forEachIndexed { index, name ->
            assertEquals("DROP TRIGGER IF EXISTS $name", tail[index * 2])
            assertTrue("$name is recreated", tail[index * 2 + 1].startsWith("CREATE TRIGGER $name"))
        }
    }

    /** The statements a migration executes, in order, without a database. */
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

    /** The table createSql followed by each index createSql, as Room exported them for [table]. */
    private fun exportedCreateSql(table: String): List<String> {
        val json = exported.readText()
        val start = json.indexOf("\"tableName\": \"$table\"")
        require(start >= 0) { "$table not in ${exported.path}" }
        val end = listOf(json.indexOf("\"tableName\"", start + 1), json.indexOf("\"setupQueries\"", start))
            .filter { it >= 0 }
            .min()
        return CREATE_SQL.findAll(json.substring(start, end))
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\${TABLE_NAME}", table) }
            .toList()
    }

    private companion object {
        val CREATE_SQL = Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
