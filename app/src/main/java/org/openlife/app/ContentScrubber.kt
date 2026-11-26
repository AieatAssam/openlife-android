package org.openlife.app

/**
 * P1-20-R4 (AS-4): one registry of everything that holds decoded content
 * (thumbnails, viewer images, OCR text, search results). [scrub] runs every
 * clearer synchronously on the caller's thread: MainActivity.onStop (hidden),
 * memory trim, and before a vault reset. Navigation is not part of it.
 */
class ContentScrubber {
    private val clearers = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    private var hiddenScrubCount = 0L

    /** How many times hiding the app has scrubbed content. */
    val hiddenScrubs: Long get() = hiddenScrubCount

    @Synchronized
    fun register(clearer: (hidden: Boolean) -> Unit): () -> Unit {
        clearers += clearer
        return { synchronized(this) { clearers.remove(clearer) } }
    }

    fun scrub(hidden: Boolean) {
        val current = synchronized(this) {
            if (hidden) hiddenScrubCount++
            clearers.toList()
        }
        current.forEach { it(hidden) }
    }
}
