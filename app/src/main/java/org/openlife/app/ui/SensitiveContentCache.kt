package org.openlife.app.ui

/**
 * Small synchronized in-memory LRU cache for content-bearing UI objects.
 * Clearing increments a generation so a decode that was already in flight
 * cannot put plaintext back into the cache after the activity has gone to
 * the background. The eviction callback is responsible for releasing native
 * resources such as [android.graphics.Bitmap] instances.
 *
 * The cache is generic and takes its value-size function from the caller so
 * the policy remains JVM-testable without an [android.graphics.Bitmap]
 * dependency.
 */
internal class SensitiveContentCache<K, V>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val sizeOf: (V) -> Long = { 0L },
    private val onEvict: (V) -> Unit,
) {
    private val values = LinkedHashMap<K, V>(maxEntries, LOAD_FACTOR, true)
    private var byteCount = 0L
    private var currentGeneration = 0L

    init {
        require(maxEntries > 0) { "Maximum cache entries must be positive" }
        require(maxBytes >= 0L) { "Maximum cache bytes cannot be negative" }
    }

    @Synchronized
    fun generation(): Long = currentGeneration

    @Synchronized
    fun contains(key: K): Boolean = values.containsKey(key)

    @Synchronized
    fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V, expectedGeneration: Long): Boolean {
        if (expectedGeneration != currentGeneration) {
            onEvict(value)
            return false
        }

        values.remove(key)?.let { previous ->
            byteCount -= bytesOf(previous)
            onEvict(previous)
        }
        values[key] = value
        byteCount = safeAdd(byteCount, bytesOf(value))
        evictUntilWithinBounds()
        return values.containsKey(key)
    }

    @Synchronized
    fun remove(key: K): Boolean {
        if (!values.containsKey(key)) return false
        val value = values.remove(key) ?: return true
        byteCount -= bytesOf(value)
        onEvict(value)
        return true
    }

    @Synchronized
    fun clear() {
        currentGeneration++
        values.values.forEach(onEvict)
        values.clear()
        byteCount = 0L
    }

    private fun evictUntilWithinBounds() {
        while (values.size > maxEntries || byteCount > maxBytes) {
            val iterator = values.entries.iterator()
            if (!iterator.hasNext()) return
            val eldest = iterator.next()
            iterator.remove()
            byteCount -= bytesOf(eldest.value)
            onEvict(eldest.value)
        }
    }

    private fun bytesOf(value: V): Long = sizeOf(value).coerceAtLeast(0L)

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    companion object {
        internal const val DEFAULT_MAX_ENTRIES = 64
        internal const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L
        private const val LOAD_FACTOR = 0.75f
    }
}
