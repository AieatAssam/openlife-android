package org.openlife.app.ui

/**
 * Small synchronized in-memory cache for content-bearing UI objects. Clearing
 * increments a generation so a decode that was already in flight cannot put
 * plaintext back into the cache after the activity has gone to the
 * background. The eviction callback is responsible for releasing native
 * resources such as [android.graphics.Bitmap] instances.
 */
internal class SensitiveContentCache<K, V>(
    private val onEvict: (V) -> Unit,
) {
    private val values = mutableMapOf<K, V>()
    private var currentGeneration = 0L

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
        values.put(key, value)?.let(onEvict)
        return true
    }

    @Synchronized
    fun remove(key: K): Boolean {
        if (!values.containsKey(key)) return false
        values.remove(key)?.let(onEvict)
        return true
    }

    @Synchronized
    fun clear() {
        currentGeneration++
        values.values.forEach(onEvict)
        values.clear()
    }
}
