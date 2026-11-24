package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The SQL-level READY invariant (docs/decisions/0001-c0-defaults.md item 8):
 * a `sources` row with state READY must have every validated field. One
 * definition, used by [OpenLifeDatabase.readyInvariantCallback] for a fresh
 * database and by every migration (P1-18-R12, V-11).
 *
 * Rule: any future migration that touches `sources` (in particular one that
 * rebuilds it, which drops its triggers) MUST call [create] after the table
 * is in its final shape. The DDL is unchanged from P1-14; no schema change.
 */
object ReadyInvariantTriggers {
    private val events = listOf("INSERT", "UPDATE")

    val names: List<String> = events.map { "sources_ready_requires_fields_$it" }

    /** Drops and recreates both triggers; idempotent. */
    fun create(db: SupportSQLiteDatabase) {
        for (event in events) {
            db.execSQL("DROP TRIGGER IF EXISTS sources_ready_requires_fields_$event")
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
                """.trimIndent(),
            )
        }
    }
}
