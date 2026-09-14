package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentCacheTest {

    @Test
    fun clearEvictsSensitiveValuesAndInvalidatesInFlightGeneration() {
        val evicted = mutableListOf<String>()
        val cache = SensitiveContentCache<String, String> { evicted += it }
        val generation = cache.generation()

        cache.put("preview", "plaintext", generation)
        assertEquals("plaintext", cache.get("preview"))

        cache.clear()

        assertNull(cache.get("preview"))
        assertEquals(listOf("plaintext"), evicted)
        assertTrue(cache.generation() > generation)
        assertFalse(cache.put("late", "late plaintext", generation))
        assertEquals(listOf("plaintext", "late plaintext"), evicted)
    }
}
