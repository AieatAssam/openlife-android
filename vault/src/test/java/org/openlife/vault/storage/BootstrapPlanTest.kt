package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.crypto.Envelope

/** P1-18-R4 (V-02) and R10 (V-09): bootstrap's disk-state decisions, on the JVM. */
class BootstrapPlanTest {
    private val envelope = Envelope(ByteArray(12) { 1 }, ByteArray(48) { 2 })
    private val present: () -> KeyRead = { KeyRead.Present(envelope) }
    private val corrupt: () -> KeyRead = { KeyRead.Corrupt }
    private val unread: () -> KeyRead = { error("the key must not be read for this decision") }

    @Test
    fun keyAndArtefactsWithoutDatabaseIsDatabaseMissingBeforeTheKeyIsRead() {
        assertEquals(
            BootstrapPlan.Stop(VaultUnavailableCause.DATABASE_MISSING),
            planBootstrap(keyFileExists = true, readKey = unread, databaseExists = false, hasArtefacts = true),
        )
    }

    @Test
    fun tornKeyWithNothingAtStakeIsAFreshInstall() {
        assertEquals(
            BootstrapPlan.CreateFresh,
            planBootstrap(keyFileExists = true, readKey = corrupt, databaseExists = false, hasArtefacts = false),
        )
    }

    @Test
    fun corruptKeyNextToADatabaseStaysCorrupt() {
        assertEquals(
            BootstrapPlan.Stop(VaultUnavailableCause.KEY_FILE_CORRUPT),
            planBootstrap(keyFileExists = true, readKey = corrupt, databaseExists = true, hasArtefacts = false),
        )
    }

    @Test
    fun existingKeyIsReusedWithOrWithoutADatabaseWhenNoArtefactIsOrphaned() {
        assertEquals(
            BootstrapPlan.UnwrapExisting(envelope),
            planBootstrap(keyFileExists = true, readKey = present, databaseExists = true, hasArtefacts = true),
        )
        // design §10: an interrupted fresh bootstrap retries with its key.
        assertEquals(
            BootstrapPlan.UnwrapExisting(envelope),
            planBootstrap(keyFileExists = true, readKey = present, databaseExists = false, hasArtefacts = false),
        )
    }

    @Test
    fun noKeyFollowsTheExistingRules() {
        assertEquals(
            BootstrapPlan.Stop(VaultUnavailableCause.DATABASE_WITHOUT_KEY),
            planBootstrap(
                keyFileExists = false,
                readKey = { KeyRead.Absent },
                databaseExists = true,
                hasArtefacts = false,
            ),
        )
        assertEquals(
            BootstrapPlan.CreateFresh,
            planBootstrap(
                keyFileExists = false,
                readKey = { KeyRead.Absent },
                databaseExists = false,
                hasArtefacts = false,
            ),
        )
    }
}
