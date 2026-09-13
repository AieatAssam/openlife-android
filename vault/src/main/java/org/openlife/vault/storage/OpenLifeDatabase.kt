package org.openlife.vault.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v1: one Source table and Room's own bookkeeping tables. No Fact,
 * Record, or Action table exists yet — design §5/§9 are explicit that later
 * schema is added only when its capability begins.
 */
@Database(entities = [SourceEntity::class], version = 1, exportSchema = true)
abstract class OpenLifeDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

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
    }
}
