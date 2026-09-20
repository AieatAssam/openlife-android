package org.openlife.app.ui

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.SensitiveContentTrimPolicy

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

    @Test
    fun evictsLeastRecentlyUsedWhenEntryLimitExceeded() {
        val evicted = mutableListOf<String>()
        val cache = SensitiveContentCache<String, String>(
            onEvict = { evicted += it },
            maxEntries = 2,
            maxBytes = Long.MAX_VALUE,
            sizeOf = { 1L },
        )
        val generation = cache.generation()

        cache.put("a", "alpha", generation)
        cache.put("b", "bravo", generation)
        cache.put("c", "charlie", generation)

        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
        assertEquals(listOf("alpha"), evicted)
    }

    @Test
    fun evictsWhenByteBudgetExceeded() {
        val evicted = mutableListOf<String>()
        val cache = SensitiveContentCache<String, String>(
            onEvict = { evicted += it },
            maxEntries = 10,
            maxBytes = 5L,
            sizeOf = { it.length.toLong() },
        )
        val generation = cache.generation()

        cache.put("large", "1234", generation)
        cache.put("small", "12", generation)

        assertFalse(cache.contains("large"))
        assertTrue(cache.contains("small"))
        assertEquals(listOf("1234"), evicted)
    }

    @Test
    fun getRefreshesRecency() {
        val evicted = mutableListOf<String>()
        val cache = SensitiveContentCache<String, String>(
            onEvict = { evicted += it },
            maxEntries = 2,
            maxBytes = Long.MAX_VALUE,
            sizeOf = { 1L },
        )
        val generation = cache.generation()

        cache.put("a", "alpha", generation)
        cache.put("b", "bravo", generation)
        assertEquals("alpha", cache.get("a"))
        cache.put("c", "charlie", generation)

        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("b"))
        assertEquals(listOf("bravo"), evicted)
    }

    @Test
    fun negativeResultsAreCachedWithoutConsumingByteBudget() {
        val cache = SensitiveContentCache<String, String?>(
            onEvict = {},
            maxEntries = 2,
            maxBytes = 1L,
            sizeOf = { it?.length?.toLong() ?: 0L },
        )
        val generation = cache.generation()

        assertTrue(cache.put("missing", null, generation))
        cache.put("present", "1", generation)

        assertTrue(cache.contains("missing"))
        assertTrue(cache.contains("present"))
    }

    @Test
    fun trimPolicyClearsAtRunningLowAndUiHidden() {
        assertTrue(SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(SensitiveContentTrimPolicy.shouldClear(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
    }
}
