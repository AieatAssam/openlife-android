package org.openlife.vault.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.model.AssessmentState
import org.openlife.vault.model.UserContentKind

/**
 * P2-07-R1/R2/R3 on SQLCipher: a version-3 vault with rows migrates to v4
 * through MIGRATION_3_4 (validated by Room against the exported 4.json);
 * every existing row reads NOT_ASSESSED with no label, nothing else in the
 * row changes, the READY triggers still exist and fire, and the label write
 * is a READY-only compare-and-set that the UPDATE trigger accepts.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SourceDatabaseTest {

    @Test
    fun migrationAddsAssessmentColumnsWithNotAssessedDefaultAndPreservesRows() {
        val passphrase = ByteArray(32) { 13 }
        val name = "p207-assessment-${UUID.randomUUID()}"
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(passphrase.copyOf()),
        )
        val v3 = helper.createDatabase(name, 3)
        v3.execSQL(
            """
            INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                orientation, wrappedDek, artefactVersion)
            VALUES ('s1', 'READY', 1, 'SHARE', 'image/jpeg', 10, X'01', 10, 10, 'NORMAL', X'02', 1),
                   ('s2', 'STAGED', 2, 'PHOTO_PICKER', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
        )
        v3.close()

        val migrated = helper.runMigrationsAndValidate(name, 4, true, *OpenLifeDatabase.MIGRATIONS.toTypedArray())
        try {
            migrated.query(
                "SELECT id, state, sha256, assessmentState, userContentKind, userContentKindAt, " +
                    "assessmentMethodVersion FROM sources ORDER BY id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("s1", cursor.getString(0))
                assertEquals("READY", cursor.getString(1))
                assertEquals(listOf<Byte>(1), cursor.getBlob(2).toList())
                assertEquals("NOT_ASSESSED", cursor.getString(3))
                assertTrue(cursor.isNull(4))
                assertTrue(cursor.isNull(5))
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.moveToNext())
                assertEquals("s2", cursor.getString(0))
                assertEquals("STAGED", cursor.getString(1))
                assertEquals("NOT_ASSESSED", cursor.getString(3))
                assertTrue(cursor.isNull(4))
            }
            // A new row that names none of the columns also gets the default.
            migrated.execSQL(
                """
                INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                    orientation, wrappedDek, artefactVersion)
                VALUES ('s3', 'READY', 3, 'SHARE', 'image/png', 30, X'03', 30, 30, 'NORMAL', X'04', 1)
                """.trimIndent(),
            )
            migrated.query("SELECT assessmentState FROM sources WHERE id = 's3'").use {
                assertTrue(it.moveToFirst())
                assertEquals("NOT_ASSESSED", it.getString(0))
            }
            val triggers = mutableListOf<String>()
            migrated.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = 'sources' ORDER BY name")
                .use { cursor -> while (cursor.moveToNext()) triggers += cursor.getString(0) }
            assertEquals(ReadyInvariantTriggers.names.sorted(), triggers)
            val rejected = runCatching {
                migrated.execSQL(
                    """
                    INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                        orientation, wrappedDek, artefactVersion)
                    VALUES ('bad', 'READY', 1, 'SHARE', NULL, 10, X'01', 10, 10, 'NORMAL', X'02', 1)
                    """.trimIndent(),
                )
            }
            assertTrue(
                "the READY trigger fires after the v4 migration: ${rejected.exceptionOrNull()}",
                rejected.exceptionOrNull()?.message?.contains("READY source requires all validated fields") == true,
            )
        } finally {
            migrated.close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = OpenLifeDatabaseFactory.create(
            context = context,
            paths = VaultPaths(context),
            databaseSecret = passphrase.copyOf(),
            databasePath = context.getDatabasePath(name).absolutePath,
        )
        try {
            runBlocking {
                val dao = reopened.sourceDao()
                val before = dao.findById("s1")!!.toDomain()
                assertEquals(AssessmentState.NOT_ASSESSED, before.assessmentState)

                assertEquals(1, dao.setUserContentKind("s1", UserContentKind.COMMUNICATION.name, 500))
                val labelled = dao.findById("s1")!!.toDomain()
                assertEquals(UserContentKind.COMMUNICATION, labelled.userContentKind)
                assertEquals(500L, labelled.userContentKindAt)
                assertEquals("the label never changes the assessment", AssessmentState.NOT_ASSESSED, labelled.assessmentState)
                assertEquals(before.copy(userContentKind = UserContentKind.COMMUNICATION, userContentKindAt = 500), labelled)

                // "Not sure": no kind, but the answer is attributed.
                assertEquals(1, dao.setUserContentKind("s1", null, 600))
                val notSure = dao.findById("s1")!!.toDomain()
                assertNull(notSure.userContentKind)
                assertEquals(600L, notSure.userContentKindAt)

                assertEquals("a STAGED row is not labelled", 0, dao.setUserContentKind("s2", "COMMUNICATION", 700))
                assertNull(dao.findById("s2")!!.userContentKindAt)
                assertEquals("a missing row is not labelled", 0, dao.setUserContentKind("missing", null, 700))
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
