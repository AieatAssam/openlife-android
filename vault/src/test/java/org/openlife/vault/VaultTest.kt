package org.openlife.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placeholder to prove the JVM unit test task actually runs in this module.
 * Replaced by real crypto/model tests starting Stage 1.
 */
class VaultTest {
    @Test
    fun schemaVersionIsOne() {
        assertEquals(1, Vault.SCHEMA_VERSION)
    }
}
