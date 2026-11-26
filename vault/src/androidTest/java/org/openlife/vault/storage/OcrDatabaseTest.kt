package org.openlife.vault.storage

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
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
        db.ocrSpanReviewDao().append(UUID.randomUUID().toString(), spanId.toString(), OcrReviewState.REJECTED.name, 3)

        db.sourceDao().deleteById(sourceId.toString())

        assertEquals(0, db.ocrDao().countRevisions())
        assertEquals(0, db.ocrDao().countSpans())
        assertEquals(0, db.ocrDao().countUserRevisions())
        assertEquals(0, db.ocrSpanReviewDao().count())
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

    /**
     * P2-06-R5 / R11 (E-04): schema v3 adds ocr_span_reviews on SQLCipher, the
     * row survives a reopen through the production factory and callback, and
     * deleting the span cascades the review.
     */
    @Test
    fun migrationAddsSpanReviewsAndCascades() {
        val passphrase = ByteArray(32) { 9 }
        val cipherHelper = sqlCipherHelper(passphrase)
        val databaseName = "p206-span-reviews-${UUID.randomUUID()}"
        val sourceId = UUID.randomUUID().toString()
        val revisionId = UUID.randomUUID().toString()
        val spanId = UUID.randomUUID().toString()
        val reviewId = UUID.randomUUID().toString()
        val created = cipherHelper.createDatabase(databaseName, 2)
        created.execSQL(
            """
            INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                orientation, wrappedDek, artefactVersion)
            VALUES ('$sourceId', 'READY', 1, 'SHARE', 'image/jpeg', 10, X'01', 10, 10, 'NORMAL', X'02', 1)
            """.trimIndent(),
        )
        created.execSQL(
            """
            INSERT INTO ocr_revisions(id, sourceId, state, engineId, modelVersion, orientation, sourceDigest,
                startedAt, extractedAt, reviewState, failureReason, charCount, spanCount)
            VALUES ('$revisionId', '$sourceId', 'READY', 'mlkit-latin', '16.0.1', 'NORMAL', X'03', 1, 2,
                'UNREVIEWED', NULL, 4, 1)
            """.trimIndent(),
        )
        created.execSQL(
            """
            INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, left, top, right, bottom)
            VALUES ('$spanId', '$revisionId', 0, 'hello', NULL, 'SOURCE_PIXELS', 1, 2, 3, 4)
            """.trimIndent(),
        )
        created.close()

        val migrated = cipherHelper.runMigrationsAndValidate(
            databaseName,
            currentDatabaseVersion(),
            // P2-08: v5's FTS5 tables are not entities, so the dropped-table scan would reject them;
            // every entity table is still validated, and NonEntityTables checks the rest (W-04).
            false,
            *OpenLifeDatabase.MIGRATIONS.toTypedArray(),
        )
        NonEntityTables.assertOnlySearchIndex(migrated, currentDatabaseVersion())
        val tableExists = migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'ocr_span_reviews'",
        ).use { it.moveToFirst() }
        assertTrue("ocr_span_reviews must exist after the v2 to v3 migration", tableExists)
        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            """
            INSERT INTO ocr_span_reviews(id, spanId, reviewState, createdAt, sequence)
            VALUES ('$reviewId', '$spanId', 'REJECTED', 5, 1)
            """.trimIndent(),
        )
        migrated.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = openWithProductionFactory(databaseName, passphrase)
        runBlocking {
            val review = reopened.ocrSpanReviewDao().findForSpan(spanId).last()
            assertEquals("REJECTED", review.reviewState)
            assertEquals(1, reopened.ocrSpanReviewDao().count())
            reopened.openHelper.writableDatabase.execSQL("DELETE FROM ocr_spans WHERE id = '$spanId'")
            assertEquals(0, reopened.ocrSpanReviewDao().count())
        }
        reopened.close()
        context.deleteDatabase(databaseName)
    }

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

        val migrated = helper.runMigrationsAndValidate(
            name,
            currentDatabaseVersion(),
            // P2-08: v5's FTS5 tables are not entities, so the dropped-table scan would reject them;
            // every entity table is still validated, and NonEntityTables checks the rest (W-04).
            false,
            *OpenLifeDatabase.MIGRATIONS.toTypedArray(),
        )
        NonEntityTables.assertOnlySearchIndex(migrated, currentDatabaseVersion())
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

    /**
     * P1-18-R12 (V-11): the READY-invariant triggers come from the migration
     * path itself, not only from onCreate. A version-1 database created
     * without them (as a migration that rebuilds `sources` would leave it)
     * has both triggers, by name, and they fire, after migrating to the
     * current @Database version.
     */
    @Test
    fun readyInvariantTriggersExistAfterMigratingToCurrentVersion() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenLifeDatabase::class.java,
            emptyList(),
            SupportOpenHelperFactory(ByteArray(32) { 6 }),
        )
        val name = "p118-triggers-${UUID.randomUUID()}"
        helper.createDatabase(name, 1).close() // no callback: no triggers

        val migrated = helper.runMigrationsAndValidate(
            name,
            currentDatabaseVersion(),
            // P2-08: v5's FTS5 tables are not entities, so the dropped-table scan would reject them;
            // every entity table is still validated, and NonEntityTables checks the rest (W-04).
            false,
            *OpenLifeDatabase.MIGRATIONS.toTypedArray(),
        )
        NonEntityTables.assertOnlySearchIndex(migrated, currentDatabaseVersion())
        try {
            val triggers = mutableListOf<String>()
            migrated.query(READY_TRIGGERS_QUERY)
                .use { cursor -> while (cursor.moveToNext()) triggers += cursor.getString(0) }
            assertEquals(
                listOf("sources_ready_requires_fields_INSERT", "sources_ready_requires_fields_UPDATE"),
                triggers,
            )
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
                "the migrated trigger fires: ${rejected.exceptionOrNull()}",
                rejected.exceptionOrNull()?.message?.contains("READY source requires all validated fields") == true,
            )
        } finally {
            migrated.close()
            InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
        }
    }

    /**
     * P2-06-R11 (E-04): a version-2 SQLCipher vault with rows upgrades to the
     * current @Database version through the production factory (SQLCipher,
     * every migration, readyInvariantCallback). Room validates the migrated
     * schema on open; row counts survive and the READY triggers, absent at v2,
     * exist and fire.
     */
    @Test
    fun sqlcipherMigrationToCurrentVersionKeepsRowsAndReadyTriggers() {
        val passphrase = ByteArray(32) { 11 }
        val name = "p206-sqlcipher-upgrade-${UUID.randomUUID()}"
        // No callback at v2: the triggers are absent, so finding them after the
        // upgrade proves MIGRATION_2_3 re-creates them (opening a migrated
        // database does not run onCreate).
        val v2 = sqlCipherHelper(passphrase).createDatabase(name, 2)
        v2.execSQL(
            """
            INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                orientation, wrappedDek, artefactVersion)
            VALUES ('s1', 'READY', 1, 'SHARE', 'image/jpeg', 10, X'01', 10, 10, 'NORMAL', X'02', 1),
                   ('s2', 'READY', 2, 'PHOTO_PICKER', 'image/png', 20, X'03', 20, 20, 'ROTATE_90', X'04', 1)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO ocr_revisions(id, sourceId, state, engineId, modelVersion, orientation, sourceDigest,
                startedAt, extractedAt, reviewState, failureReason, charCount, spanCount)
            VALUES ('r1', 's1', 'READY', 'mlkit-latin', '16.0.1', 'NORMAL', X'05', 1, 2, 'ACCEPTED', NULL, 9, 2)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO ocr_spans(id, revisionId, ordinal, text, confidence, coordinateSystem, left, top, right, bottom)
            VALUES ('sp1', 'r1', 0, 'hello', NULL, 'SOURCE_PIXELS', 1, 2, 3, 4),
                   ('sp2', 'r1', 1, 'world', 0.5, 'SOURCE_PIXELS', NULL, NULL, NULL, NULL)
            """.trimIndent(),
        )
        v2.execSQL(
            """
            INSERT INTO ocr_user_revisions(id, revisionId, spanId, correctedText, createdAt, actor)
            VALUES ('u1', 'r1', 'sp1', 'hullo', 3, 'USER')
            """.trimIndent(),
        )
        v2.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val upgraded = openWithProductionFactory(name, passphrase)
        try {
            val sql = upgraded.openHelper.writableDatabase
            assertEquals(currentDatabaseVersion(), sql.version)
            fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(2, count("sources"))
            assertEquals(1, count("ocr_revisions"))
            assertEquals(2, count("ocr_spans"))
            assertEquals(1, count("ocr_user_revisions"))
            assertEquals(0, count("ocr_span_reviews"))
            val triggers = mutableListOf<String>()
            sql.query(READY_TRIGGERS_QUERY)
                .use { cursor -> while (cursor.moveToNext()) triggers += cursor.getString(0) }
            assertEquals(ReadyInvariantTriggers.names.sorted(), triggers)
            val rejected = runCatching {
                sql.execSQL(
                    """
                    INSERT INTO sources(id, state, importedAt, intakeKind, mimeType, byteCount, sha256, width, height,
                        orientation, wrappedDek, artefactVersion)
                    VALUES ('bad', 'READY', 1, 'SHARE', NULL, 10, X'01', 10, 10, 'NORMAL', X'02', 1)
                    """.trimIndent(),
                )
            }
            assertTrue(
                "the READY trigger fires after the upgrade: ${rejected.exceptionOrNull()}",
                rejected.exceptionOrNull()?.message?.contains("READY source requires all validated fields") == true,
            )
            runBlocking {
                upgraded.ocrSpanReviewDao().append("rv1", "sp1", "REJECTED", 4)
                assertEquals(1, upgraded.ocrSpanReviewDao().count())
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    /** P2-06-R12 (E-05): the later append is current even when its createdAt is earlier. */
    @Test
    fun latestSpanReviewIsChosenBySequenceNotClock(): Unit = runBlocking {
        val sourceId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val spanId = UUID.randomUUID()
        db.sourceDao().insert(readySource(sourceId))
        db.ocrDao().insertRevision(revision(revisionId, sourceId).copy(state = OcrRevisionState.READY, extractedAt = 2).toEntity())
        db.ocrDao().insertSpans(listOf(span(revisionId, spanId).toEntity()))
        val reviews = db.ocrSpanReviewDao()

        reviews.append(UUID.randomUUID().toString(), spanId.toString(), OcrReviewState.REJECTED.name, 1_000)
        reviews.append(UUID.randomUUID().toString(), spanId.toString(), OcrReviewState.UNREVIEWED.name, 10)

        val stored = reviews.findForSpan(spanId.toString())
        assertEquals(listOf(1L, 2L), stored.map { it.sequence })
        val observed = reviews.observeForRevision(revisionId.toString()).first()
        val view = org.openlife.vault.repository.ocrViewOf(
            db.ocrDao().findRevision(revisionId.toString())!!,
            emptyList(),
            db.ocrDao().findSpans(revisionId.toString()),
            emptyList(),
            observed,
        )
        assertEquals(OcrReviewState.UNREVIEWED, view.spans.single().review?.reviewState)
        assertEquals(
            OcrReviewState.UNREVIEWED,
            org.openlife.vault.ocr.OcrSpanReviewRules.latest(stored.map { it.toDomain() })?.reviewState,
        )
    }

    private fun sqlCipherHelper(passphrase: ByteArray) = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OpenLifeDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(passphrase.copyOf()),
    )

    /** As the app opens the vault: SQLCipher, every migration, and the READY-invariant callback. */
    private fun openWithProductionFactory(name: String, passphrase: ByteArray): OpenLifeDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return OpenLifeDatabaseFactory.create(
            context = context,
            paths = VaultPaths(context),
            databaseSecret = passphrase.copyOf(),
            databasePath = context.getDatabasePath(name).absolutePath,
        )
    }

    /** The @Database version, read from a database Room just created (not Vault.SCHEMA_VERSION). */
    private fun currentDatabaseVersion(): Int = db.openHelper.readableDatabase.version

    private companion object {
        /** The READY-invariant triggers on `sources`; C2 adds its own pointer trigger there (P3-02). */
        const val READY_TRIGGERS_QUERY = "SELECT name FROM sqlite_master WHERE type = 'trigger' " +
            "AND tbl_name = 'sources' AND name LIKE 'sources_ready%' ORDER BY name"
    }
}
