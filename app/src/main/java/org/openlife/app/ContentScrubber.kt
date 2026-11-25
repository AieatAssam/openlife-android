package org.openlife.app

/**
 * P1-20-R4 (AS-4): one registry of everything that holds decoded content.
 * [scrub] runs every clearer synchronously on the caller's thread.
 */
class ContentScrubber {
    val hiddenScrubs: Long get() = TODO()

    fun register(clearer: (hidden: Boolean) -> Unit): () -> Unit = TODO()

    fun scrub(hidden: Boolean): Unit = TODO()
}
