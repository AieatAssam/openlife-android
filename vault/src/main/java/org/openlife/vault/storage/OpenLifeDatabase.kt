package org.openlife.vault.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v3 adds per-span review metadata; v4 adds the communication
 * assessment state and the user's content-kind label to `sources`. Fact,
 * Record, and Action remain absent — later capabilities add their own
 * migrations.
 */
@Database(
    entities = [
        SourceEntity::class,
        OcrRevisionEntity::class,
        OcrSpanEntity::class,
        OcrUserRevisionEntity::class,
        OcrSpanReviewEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class OpenLifeDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun ocrDao(): OcrDao

    abstract fun ocrViewDao(): OcrViewDao

    abstract fun ocrSpanReviewDao(): OcrSpanReviewDao

    companion object {
        /**
         * Room 2.8.x has no declarative way to express "every validated
         * field is non-null once state = READY" as a table-level CHECK
         * constraint on the entity (see
         * docs/decisions/0001-c0-defaults.md item 8). SQLite triggers can be
         * added after `CREATE TABLE` without disturbing Room's own schema
         * bookkeeping, so the same invariant is enforced here at the SQL
         * level as a second, independent layer under the repository's own
         * enforcement (design §9).
         */
        val readyInvariantCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                ReadyInvariantTriggers.create(db)
            }
        }

        /** Every migration, in order; never destructive (design §10). */
        val MIGRATIONS: List<androidx.room.migration.Migration> by lazy {
            listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        }

        /**
         * P2-07-R1/R2: the communication assessment state (every existing row
         * becomes NOT_ASSESSED through the column default) and the user's
         * optional content-kind label with its USER timestamp. Additive only;
         * each statement equals Room's exported v4 column (SchemaV4MigrationTest).
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `assessmentState` TEXT NOT NULL DEFAULT 'NOT_ASSESSED'")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `userContentKind` TEXT")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `userContentKindAt` INTEGER")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `assessmentMethodVersion` TEXT")
                // P1-18-R12: every migration ends by re-asserting the READY
                // invariant triggers on `sources`.
                ReadyInvariantTriggers.create(db)
            }
        }

        /**
         * Adds per-span review rows (P2-06-R5). The `sequence` column and its
         * unique (spanId, sequence) index (P2-06-R12) were added to this
         * migration in place: schema v3 had not shipped in any release or tag
         * when it was amended. The DDL equals Room's exported v3 schema
         * (SchemaV3MigrationTest).
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ocr_span_reviews` (`id` TEXT NOT NULL, `spanId` TEXT NOT NULL, " +
                        "`reviewState` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`spanId`) REFERENCES `ocr_spans`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_span_reviews_spanId_sequence` " +
                        "ON `ocr_span_reviews` (`spanId`, `sequence`)",
                )
                // P1-18-R12: every migration ends by re-asserting the READY
                // invariant triggers on `sources`.
                ReadyInvariantTriggers.create(db)
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ocr_revisions` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `engineId` TEXT NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `orientation` TEXT NOT NULL,
                        `sourceDigest` BLOB NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `extractedAt` INTEGER,
                        `reviewState` TEXT NOT NULL,
                        `failureReason` TEXT,
                        `charCount` INTEGER NOT NULL,
                        `spanCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_revisions_sourceId` ON `ocr_revisions` (`sourceId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ocr_spans` (
                        `id` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `confidence` REAL,
                        `coordinateSystem` TEXT NOT NULL,
                        `left` INTEGER,
                        `top` INTEGER,
                        `right` INTEGER,
                        `bottom` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`revisionId`) REFERENCES `ocr_revisions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_spans_revisionId` ON `ocr_spans` (`revisionId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_spans_revisionId_ordinal` " +
                        "ON `ocr_spans` (`revisionId`, `ordinal`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ocr_user_revisions` (
                        `id` TEXT NOT NULL,
                        `revisionId` TEXT NOT NULL,
                        `spanId` TEXT,
                        `correctedText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `actor` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`revisionId`) REFERENCES `ocr_revisions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`spanId`) REFERENCES `ocr_spans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ocr_user_revisions_revisionId` " +
                        "ON `ocr_user_revisions` (`revisionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ocr_user_revisions_spanId` " +
                        "ON `ocr_user_revisions` (`spanId`)",
                )
                // P1-18-R12: last, once `sources` is in its final shape, so a
                // database that lacks the triggers (or a future rebuild of
                // `sources`) ends with them.
                ReadyInvariantTriggers.create(db)
            }
        }
    }
}
