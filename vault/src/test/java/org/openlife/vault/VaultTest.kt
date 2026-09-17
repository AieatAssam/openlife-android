package org.openlife.vault

import androidx.room.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.openlife.vault.storage.OpenLifeDatabase

class VaultTest {
    @Test
    fun schemaVersionMatchesRoomDatabaseVersion() {
        val database = OpenLifeDatabase::class.java.getAnnotation(Database::class.java)
        assertNotNull("Room @Database must be visible to JVM reflection", database)

        assertEquals(database!!.version, Vault.SCHEMA_VERSION)
    }
}
