package org.openlife.vault.storage

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrReviewState
import org.openlife.vault.ocr.OcrRevisionState
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.ocr.OcrUserRevision
import org.openlife.vault.model.Orientation

@RunWith(AndroidJUnit4::class)
class OcrDatabaseTest {
    private val migrationHelper by lazy {
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )
    }
    private lateinit var db: OpenLifeDatabase

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, OpenLifeDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory("test-passphrase".toByteArray()))
            .addCallback(OpenLifeDatabase.readyInvariantCallback)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun revisionSpansAndCorrectionsRoundTripWithoutInventingEvidence(): Unit = runBlocking {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val spanId = UUID.randomUUID()
        db.sourceDao().insert(readySource(sourceId))
        db.ocrDao().insertRevision(
            org.openlife.vault.ocr.OcrRevision(
                id = revisionId,
                sourceId = sourceId,
                state = OcrRevisionState.READY,
                engineId = "mlkit-latin",
                modelVersion = "16.0.1",
                orientation = Orientation.NORMAL,
                sourceDigest = ByteArray(32) { 7 },
                startedAt = 100,
                extractedAt = 101,
                reviewState = OcrReviewState.UNREVIEWED,
                failureReason = null,
                charCount = 5,
                spanCount = 1,
            ).toEntity()
        )
        db.ocrDao().insertSpans(
            listOf(
                OcrSpan(
                    id = spanId,
                    revisionId = revisionId,
                    ordinal = 0,
                    text = "hello",
                    confidence = null,
                    coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
                    evidenceRegion = null,
                ).toEntity(),
            ),
        )
        db.ocrDao().insertUserRevision(
            OcrUserRevision(
                id = UUID.randomUUID(),
                revisionId = revisionId,
                spanId = spanId,
                correctedText = "hullo",
                createdAt = 102,
            ).toEntity(),
        )

        assertEquals("hello", db.ocrDao().findSpans(revisionId.toString()).single().toDomain().text)
        assertNull(db.ocrDao().findSpans(revisionId.toString()).single().toDomain().evidenceRegion)
        assertEquals("hullo", db.ocrDao().findUserRevisions(revisionId.toString()).single().toDomain().correctedText)
    }

    @Test
    fun deletingSourceCascadesAllOcrRows(): Unit = runBlocking {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val spanId = UUID.randomUUID()
        db.sourceDao().insert(readySource(sourceId))
        db.ocrDao().insertRevision(revision(revisionId, sourceId).toEntity())
        db.ocrDao().insertSpans(listOf(span(revisionId, spanId).toEntity()))
        db.ocrDao().insertUserRevision(
            OcrUserRevision(UUID.randomUUID(), revisionId, spanId, "changed", 2).toEntity(),
        )

        db.sourceDao().deleteById(sourceId.toString())

        assertEquals(0, db.ocrDao().countRevisions())
        assertEquals(0, db.ocrDao().countSpans())
        assertEquals(0, db.ocrDao().countUserRevisions())
    }

    @Test
    fun runningRevisionBecomesStaleAfterProcessRecovery(): Unit = runBlocking {
        val sourceId = UUID.randomUUID()
        db.sourceDao().insert(readySource(sourceId))
        db.ocrDao().insertRevision(revision(UUID.randomUUID(), sourceId).copy(state = OcrRevisionState.RUNNING).toEntity())

        assertEquals(1, db.ocrDao().markRunningStale())
        assertEquals(OcrRevisionState.STALE, db.ocrDao().findRevisionsForSource(sourceId.toString()).single().toDomain().state)
    }

    @Test
    fun versionOneSourceRowsSurviveTheC1MigrationAndNewTablesExist() {
        val databaseName = "c1-migration-${UUID.randomUUID()}"
        val old = migrationHelper.createDatabase(databaseName, 1)
        old.execSQL(
            """
            INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height, orientation, wrappedDek, artefactVersion)
            VALUES ('source-1', 'READY', 1, 'SHARE', 'image/jpeg', 10, X'0102', 10, 10, 'NORMAL', X'0304', 1)
            """.trimIndent(),
        )
        old.close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            OpenLifeDatabase.MIGRATION_1_2,
        )
        migrated.query("SELECT state, sha256 FROM sources WHERE id = 'source-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("READY", cursor.getString(0))
            assertEquals(byteArrayOf(1, 2).toList(), cursor.getBlob(1).toList())
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'ocr_%'").use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(setOf("ocr_revisions", "ocr_spans", "ocr_user_revisions"), names.toSet())
        }
        migrated.close()
    }

    private fun revision(id: UUID, sourceId: UUID) = org.openlife.vault.ocr.OcrRevision(
        id = id,
        sourceId = sourceId,
        state = OcrRevisionState.NOT_STARTED,
        engineId = "mlkit-latin",
        modelVersion = "16.0.1",
        orientation = Orientation.NORMAL,
        sourceDigest = ByteArray(32) { 3 },
        startedAt = 1,
        extractedAt = null,
        reviewState = OcrReviewState.UNREVIEWED,
        failureReason = null,
        charCount = 0,
        spanCount = 0,
    )

    private fun span(revisionId: UUID, id: UUID) = OcrSpan(
        id = id,
        revisionId = revisionId,
        ordinal = 0,
        text = "text",
        confidence = 0.5f,
        coordinateSystem = OcrCoordinateSystem.SOURCE_PIXELS,
        evidenceRegion = OcrEvidenceRegion(1, 2, 3, 4),
    )

    private fun readySource(id: UUID) = SourceEntity(
        id = id.toString(),
        state = "READY",
        importedAt = 1,
        intakeKind = "SHARE",
        mimeType = "image/jpeg",
        byteCount = 10,
        sha256 = ByteArray(32) { 1 },
        width = 10,
        height = 10,
        orientation = "NORMAL",
        wrappedDek = ByteArray(16) { 2 },
        artefactVersion = 1,
    )

    /** P1-14-R5: the migration runs on SQLCipher and the READY invariant triggers survive it. */
    @Test
    fun migrationOnSqlCipherPreservesReadyInvariantTriggers() {
        val passphrase = ByteArray(32) { 5 }
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(passphrase.copyOf()),
        )
        val name = "p114-triggers-${UUID.randomUUID()}"
        val v1 = helper.createDatabase(name, 1)
        // A version-1 install created these through the Room callback.
        OpenLifeDatabase.readyInvariantCallback.onCreate(v1)
        v1.close()

        val migrated = helper.runMigrationsAndValidate(name, 2, true, *OpenLifeDatabase.MIGRATIONS.toTypedArray())
        fun insertReady(id: String, mimeType: String?) = runCatching {
            migrated.execSQL(
                """
                INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                    orientation, wrappedDek, artefactVersion)
                VALUES ('$id', 'READY', 1, 'SHARE', ${mimeType?.let { "'$it'" } ?: "NULL"}, 10, X'01', 10, 10,
                    'NORMAL', X'02', 1)
                """.trimIndent(),
            )
        }
        val valid = insertReady("good", "image/jpeg")
        val invalid = insertReady("bad", null)
        migrated.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)

        assertTrue("positive control: a complete READY row inserts (${valid.exceptionOrNull()})", valid.isSuccess)
        assertTrue(
            "a READY row with a NULL validated field must be rejected by the trigger: ${invalid.exceptionOrNull()}",
            invalid.exceptionOrNull()?.message?.contains("READY source requires all validated fields") == true,
        )
    }
}
