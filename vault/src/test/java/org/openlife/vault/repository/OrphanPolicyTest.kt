package org.openlife.vault.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-18 review R-08: an empty database next to saved originals is never
 * evidence that the originals are orphans (design §11: never infer that an
 * unreadable database is empty and delete its files).
 */
class OrphanPolicyTest {

    @Test
    fun blobsAreNeverOrphansOfAnEmptyDatabase() {
        assertFalse(OrphanPolicy.mayRemoveOrphans(0, listOf("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0.blob")))
        assertFalse(OrphanPolicy.mayRemoveOrphans(0, listOf("a.stage", "b.blob")))
    }

    @Test
    fun ordinaryCleanupIsUnchanged() {
        assertTrue(OrphanPolicy.mayRemoveOrphans(0, emptyList()))
        assertTrue("stages hold no saved original", OrphanPolicy.mayRemoveOrphans(0, listOf("a.stage")))
        assertTrue(OrphanPolicy.mayRemoveOrphans(1, listOf("a.blob", "b.blob")))
    }
}
