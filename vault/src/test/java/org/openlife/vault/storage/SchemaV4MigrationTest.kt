package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * P2-07-R1 under plan conventions.schema_migrations and P1-18-R12: the v3->v4
 * migration only adds the four assessment columns to `sources`, each exactly
 * as Room exported it for v4 (affinity, NOT NULL, default), both copies of
 * 4.json are identical, and the migration ends by re-asserting the READY
 * invariant triggers. Room's own validation of the migrated schema runs only
 * on a device (SourceDatabaseTest); this proves the same DDL on the JVM.
 */
class SchemaV4MigrationTest {
    private val exported = File("schemas/org.openlife.vault.storage.OpenLifeDatabase/4.json")
    private val asset = File("src/androidTest/assets/org.openlife.vault.storage.OpenLifeDatabase/4.json")

    @Test
    fun bothCopiesOfTheV4SchemaAreByteIdentical() {
        assertTrue("exported schema missing at ${exported.absolutePath}", exported.isFile)
        assertTrue("androidTest asset missing at ${asset.absolutePath}", asset.isFile)
        assertArrayEquals(exported.readBytes(), asset.readBytes())
    }

    @Test
    fun exportedV4SourcesDefaultAssessmentToNotAssessed() {
        val fields = exportedSourceFields()
        assertEquals(
            listOf(
                ExportedField("assessmentState", "TEXT", notNull = true, defaultValue = "'NOT_ASSESSED'"),
                ExportedField("userContentKind", "TEXT", notNull = false, defaultValue = null),
                ExportedField("userContentKindAt", "INTEGER", notNull = false, defaultValue = null),
                ExportedField("assessmentMethodVersion", "TEXT", notNull = false, defaultValue = null),
            ),
            fields.filter { it.name in NEW_COLUMNS },
        )
    }

    @Test
    fun migrationThreeToFourAddsExactlyTheExportedColumns() {
        val executed = record(OpenLifeDatabase.MIGRATION_3_4).filterNot { it.contains("TRIGGER") }
        val expected = exportedSourceFields().filter { it.name in NEW_COLUMNS }.map { field ->
            buildString {
                append("ALTER TABLE `sources` ADD COLUMN `${field.name}` ${field.affinity}")
                if (field.notNull) append(" NOT NULL")
                field.defaultValue?.let { append(" DEFAULT $it") }
            }
        }
        assertEquals(NEW_COLUMNS.size, expected.size)
        assertEquals("additive only: one ADD COLUMN per new field, nothing else", expected, executed)
    }

    @Test
    fun migrationThreeToFourEndsByReassertingTheReadyTriggers() {
        val executed = record(OpenLifeDatabase.MIGRATION_3_4)
        val triggers = ReadyInvariantTriggers.names
        assertTrue("the migration re-creates the triggers: $executed", executed.size >= triggers.size * 2)
        val tail = executed.takeLast(triggers.size * 2)
        triggers.forEachIndexed { index, name ->
            assertEquals("DROP TRIGGER IF EXISTS $name", tail[index * 2])
            assertTrue("$name is recreated", tail[index * 2 + 1].startsWith("CREATE TRIGGER $name"))
        }
    }

    @Test
    fun migrationsAreListedInOrderThroughVersionFour() {
        val versions = OpenLifeDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4), versions)
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

    private data class ExportedField(val name: String, val affinity: String, val notNull: Boolean, val defaultValue: String?)

    /** The `fields` entries Room exported for `sources`, in order. */
    private fun exportedSourceFields(): List<ExportedField> {
        val json = exported.readText()
        val start = json.indexOf("\"tableName\": \"sources\"")
        require(start >= 0) { "sources not in ${exported.path}" }
        val fieldsStart = json.indexOf("\"fields\"", start)
        val fieldsEnd = json.indexOf("\"primaryKey\"", fieldsStart)
        return FIELD.findAll(json.substring(fieldsStart, fieldsEnd)).map { match ->
            val body = match.value
            ExportedField(
                name = COLUMN.find(body)!!.groupValues[1],
                affinity = AFFINITY.find(body)!!.groupValues[1],
                notNull = NOT_NULL.containsMatchIn(body),
                defaultValue = DEFAULT.find(body)?.groupValues?.get(1),
            )
        }.toList()
    }

    private companion object {
        val NEW_COLUMNS = listOf("assessmentState", "userContentKind", "userContentKindAt", "assessmentMethodVersion")
        val FIELD = Regex("\\{[^{}]*\"columnName\"[^{}]*\\}")
        val COLUMN = Regex("\"columnName\": \"([^\"]+)\"")
        val AFFINITY = Regex("\"affinity\": \"([^\"]+)\"")
        val NOT_NULL = Regex("\"notNull\": true")
        val DEFAULT = Regex("\"defaultValue\": \"([^\"]*)\"")
    }
}
