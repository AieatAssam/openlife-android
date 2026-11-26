package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The C2 triggers (C2-R3, R4, R6, R7, R8, R10, R12, R14, R15, R18, R20 and
 * §4.3) and the C2 CHECK constraints, which Room cannot declare on an entity
 * and which a fresh database (created from Room's DDL) would otherwise lack.
 * One definition, used by [OpenLifeDatabase.readyInvariantCallback] for a
 * fresh database and by the migration to the C2 schema. Idempotent.
 */
object FactSchemaTriggers {
    val names: List<String> get() = TODO("P3-02 GREEN")

    fun statements(): List<String> = TODO("P3-02 GREEN")

    fun create(db: SupportSQLiteDatabase) {
        TODO("P3-02 GREEN")
    }
}
