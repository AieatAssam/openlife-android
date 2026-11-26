package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * C2-R21: the additive migration to the C2 schema (v6), as named steps run in
 * this order (ZZ-01):
 * 1. narrow the FTS update triggers ([SearchIndex.narrowUpdateTriggers]);
 * 2. add the §4.7 columns;
 * 3. backfill them;
 * 4. create the C2 tables and indices;
 * 5. create the C2 triggers ([FactSchemaTriggers]);
 * 6. re-assert the READY triggers ([ReadyInvariantTriggers]);
 * 7. [SearchIndex.ensure].
 *
 * Step 1 comes first so that steps 2-3 never re-index a span or a correction
 * (FactDatabaseTest proves `<table>_data` byte-identical). Every statement
 * equals Room's exported 6.json (FactDatabaseTest compares the migrated
 * schema with a fresh one). Room runs the whole migration in one transaction,
 * so any throw, including from the classifier, leaves v5 untouched.
 */
object FactMigration {
    private const val CHUNK = 500

    /** Step 1 (C2-R64). */
    fun narrowFtsUpdateTriggers(db: SupportSQLiteDatabase) = SearchIndex.narrowUpdateTriggers(db)

    /** Step 2: the §4.7 columns, each as Room exports it for v6. */
    fun addColumns(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sources` ADD COLUMN `currentOcrRevisionId` TEXT")
        db.execSQL("ALTER TABLE `sources` ADD COLUMN `factInputEpoch` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `ocr_spans` ADD COLUMN `hasEvidenceRegion` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `ocr_spans` ADD COLUMN `scriptStatus` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE `ocr_user_revisions` ADD COLUMN `supersedesId` TEXT")
    }

    /** Step 3: the §4.7 backfills (C2-R14, R15, R16), then the Kotlin scriptStatus classification (C2-R17). */
    fun backfill(db: SupportSQLiteDatabase, classify: (String) -> String = ::spanScriptStatusOf) {
        db.execSQL(BACKFILL_EVIDENCE_REGION)
        db.execSQL(BACKFILL_CURRENT_READING)
        db.execSQL(BACKFILL_SUPERSEDES)
        val byStatus = mutableMapOf<String, MutableList<Long>>()
        db.query("SELECT rowid, text FROM ocr_spans").use { cursor ->
            while (cursor.moveToNext()) {
                val status = SpanScriptStatus.valueOf(classify(cursor.getString(1)))
                check(status == SpanScriptStatus.LATIN || status == SpanScriptStatus.UNSUPPORTED)
                byStatus.getOrPut(status.name) { mutableListOf() } += cursor.getLong(0)
            }
        }
        byStatus.forEach { (status, rowids) ->
            rowids.chunked(CHUNK).forEach { chunk ->
                db.execSQL("UPDATE ocr_spans SET scriptStatus = '$status' WHERE rowid IN (${chunk.joinToString(",")})")
            }
        }
    }

    /** Step 4: the C2 tables and indices, byte-equal to 6.json. */
    fun createTables(db: SupportSQLiteDatabase) = TABLES.forEach(db::execSQL)

    /** Steps 1-7 in order. */
    fun migrate(db: SupportSQLiteDatabase) {
        narrowFtsUpdateTriggers(db)
        addColumns(db)
        backfill(db)
        createTables(db)
        FactSchemaTriggers.create(db)
        // P1-18-R12: every migration re-asserts the READY invariant triggers.
        ReadyInvariantTriggers.create(db)
        SearchIndex.ensure(db)
    }

    private const val BACKFILL_EVIDENCE_REGION = "UPDATE ocr_spans SET hasEvidenceRegion = 1 " +
        "WHERE `left` IS NOT NULL AND top IS NOT NULL AND `right` IS NOT NULL AND bottom IS NOT NULL"

    private const val BACKFILL_CURRENT_READING = "UPDATE sources SET currentOcrRevisionId = (" +
        "SELECT r.id FROM ocr_revisions r WHERE r.sourceId = sources.id AND r.state = 'READY' " +
        "ORDER BY r.startedAt DESC, r.rowid DESC LIMIT 1)"

    private const val BACKFILL_SUPERSEDES = "UPDATE ocr_user_revisions SET supersedesId = (" +
        "SELECT p.id FROM ocr_user_revisions p WHERE p.revisionId = ocr_user_revisions.revisionId " +
        "AND p.spanId IS ocr_user_revisions.spanId AND (p.createdAt < ocr_user_revisions.createdAt " +
        "OR (p.createdAt = ocr_user_revisions.createdAt AND p.rowid < ocr_user_revisions.rowid)) " +
        "ORDER BY p.createdAt DESC, p.rowid DESC LIMIT 1)"

    private val TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `facts` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`ordinal` INTEGER NOT NULL, `currentRevisionId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_facts_sourceId_kind_ordinal` ON `facts` (`sourceId`, " +
            "`kind`, `ordinal`)",
        "CREATE TABLE IF NOT EXISTS `fact_revisions` (`id` TEXT NOT NULL, `factId` TEXT NOT NULL, " +
            "`revisionNo` INTEGER NOT NULL, `origin` TEXT NOT NULL, `kind` TEXT NOT NULL, `valueJson` TEXT NOT NULL, " +
            "`canonicalText` TEXT NOT NULL, `rawText` TEXT, `status` TEXT NOT NULL, `staleReason` TEXT, " +
            "`staleAt` INTEGER, `confidence` REAL, `methodId` TEXT NOT NULL, `methodVersion` TEXT, `locale` TEXT, " +
            "`createdAt` INTEGER NOT NULL, `supersedesRevisionId` TEXT, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`factId`) REFERENCES `facts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`supersedesRevisionId`) REFERENCES `fact_revisions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_fact_revisions_factId_revisionNo` " +
            "ON `fact_revisions` (`factId`, `revisionNo`)",
        "CREATE INDEX IF NOT EXISTS `index_fact_revisions_supersedesRevisionId` " +
            "ON `fact_revisions` (`supersedesRevisionId`)",
        "CREATE TABLE IF NOT EXISTS `fact_evidence` (`id` TEXT NOT NULL, `revisionId` TEXT NOT NULL, " +
            "`ordinal` INTEGER NOT NULL, `role` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
            "`ocrRevisionId` TEXT NOT NULL, `ocrSpanId` TEXT NOT NULL, `ocrUserRevisionId` TEXT, " +
            "`textStart` INTEGER, `textEnd` INTEGER, `regionLeft` INTEGER, `regionTop` INTEGER, " +
            "`regionRight` INTEGER, `regionBottom` INTEGER, `regionCoordinateSystem` TEXT, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`revisionId`) REFERENCES `fact_revisions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`ocrRevisionId`) REFERENCES `ocr_revisions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`ocrSpanId`) REFERENCES `ocr_spans`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , " +
            "FOREIGN KEY(`ocrUserRevisionId`) REFERENCES `ocr_user_revisions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_fact_evidence_revisionId_ordinal` " +
            "ON `fact_evidence` (`revisionId`, `ordinal`)",
        "CREATE INDEX IF NOT EXISTS `index_fact_evidence_sourceId` ON `fact_evidence` (`sourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_fact_evidence_ocrRevisionId` ON `fact_evidence` (`ocrRevisionId`)",
        "CREATE INDEX IF NOT EXISTS `index_fact_evidence_ocrSpanId` ON `fact_evidence` (`ocrSpanId`)",
        "CREATE INDEX IF NOT EXISTS `index_fact_evidence_ocrUserRevisionId` ON `fact_evidence` (`ocrUserRevisionId`)",
        "CREATE TABLE IF NOT EXISTS `review_events` (`id` TEXT NOT NULL, `revisionId` TEXT NOT NULL, " +
            "`sequence` INTEGER NOT NULL, `decision` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`revisionId`) REFERENCES `fact_revisions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_events_revisionId_sequence` " +
            "ON `review_events` (`revisionId`, `sequence`)",
        "CREATE TABLE IF NOT EXISTS `fact_extraction_runs` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
            "`ocrRevisionId` TEXT NOT NULL, `trigger` TEXT NOT NULL, `rulesVersion` TEXT NOT NULL, " +
            "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `outcome` TEXT NOT NULL, " +
            "`skippedKinds` TEXT, `candidateCount` INTEGER, `createdRevisionCount` INTEGER, " +
            "`skippedLineCount` INTEGER, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`ocrRevisionId`) REFERENCES `ocr_revisions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        "CREATE INDEX IF NOT EXISTS `index_fact_extraction_runs_sourceId` ON `fact_extraction_runs` (`sourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_fact_extraction_runs_ocrRevisionId` " +
            "ON `fact_extraction_runs` (`ocrRevisionId`)",
        "CREATE TABLE IF NOT EXISTS `dependency_edges` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`edgeKind` TEXT NOT NULL, `dependentKind` TEXT NOT NULL, `dependentId` TEXT NOT NULL, " +
            "`dependentSourceId` TEXT NOT NULL, `dependencyKind` TEXT NOT NULL, `dependencyId` TEXT NOT NULL, " +
            "`dependencySourceId` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_dependency_edges_edgeKind_dependentKind_dependentId_dependencyKind_dependencyId` " +
            "ON `dependency_edges` (`edgeKind`, `dependentKind`, `dependentId`, `dependencyKind`, `dependencyId`)",
        "CREATE INDEX IF NOT EXISTS `index_dependency_edges_dependencyKind_dependencyId` " +
            "ON `dependency_edges` (`dependencyKind`, `dependencyId`)",
        "CREATE INDEX IF NOT EXISTS `index_dependency_edges_dependentKind_dependentId` " +
            "ON `dependency_edges` (`dependentKind`, `dependentId`)",
        "CREATE INDEX IF NOT EXISTS `index_dependency_edges_dependencySourceId` " +
            "ON `dependency_edges` (`dependencySourceId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_user_revisions_supersedesId` " +
            "ON `ocr_user_revisions` (`supersedesId`)",
    )
}
