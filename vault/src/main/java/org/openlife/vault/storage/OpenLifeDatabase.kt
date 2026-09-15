package org.openlife.vault.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v2 adds only C1's OCR provenance tables. Fact, Record, and Action
 * remain absent — later capabilities add their own migrations.
 */
@Database(
    entities = [SourceEntity::class, OcrRevisionEntity::class, OcrSpanEntity::class, OcrUserRevisionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class OpenLifeDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun ocrDao(): OcrDao

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
                for (event in listOf("INSERT", "UPDATE")) {
                    db.execSQL(
                        """
                        CREATE TRIGGER sources_ready_requires_fields_$event
                        BEFORE $event ON sources
                        WHEN NEW.state = 'READY' AND (
                            NEW.mimeType IS NULL OR
                            NEW.byteCount IS NULL OR
                            NEW.sha256 IS NULL OR
                            NEW.width IS NULL OR
                            NEW.height IS NULL OR
                            NEW.orientation IS NULL OR
                            NEW.wrappedDek IS NULL OR
                            NEW.artefactVersion IS NULL
                        )
                        BEGIN
                            SELECT RAISE(ABORT, 'READY source requires all validated fields');
                        END;
                        """.trimIndent()
                    )
                }
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
                    """.trimIndent()
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
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_spans_revisionId` ON `ocr_spans` (`revisionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_spans_revisionId_ordinal` ON `ocr_spans` (`revisionId`, `ordinal`)")
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
                        FOREIGN KEY(`revisionId`) REFERENCES `ocr_revisions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`spanId`) REFERENCES `ocr_spans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_user_revisions_revisionId` ON `ocr_user_revisions` (`revisionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ocr_user_revisions_spanId` ON `ocr_user_revisions` (`spanId`)")
            }
        }
    }
}
