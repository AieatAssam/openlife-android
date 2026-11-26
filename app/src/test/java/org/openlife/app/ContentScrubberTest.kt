package org.openlife.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** P1-20-R4: one synchronous registry clears every content holder; hiding is counted, trimming is not. */
class ContentScrubberTest {
    private val scrubber = ContentScrubber()

    @Test
    fun scrubRunsEveryClearerSynchronouslyWithTheReason() {
        val calls = mutableListOf<String>()
        scrubber.register { hidden -> calls += "first:$hidden" }
        scrubber.register { hidden -> calls += "second:$hidden" }

        scrubber.scrub(hidden = true)
        assertEquals(listOf("first:true", "second:true"), calls)
        scrubber.scrub(hidden = false)
        assertEquals(listOf("first:true", "second:true", "first:false", "second:false"), calls)
        assertEquals("only hiding counts as a background scrub", 1L, scrubber.hiddenScrubs)
    }

    @Test
    fun unregisteredClearersAreNotCalledAndMayUnregisterWhileScrubbing() {
        var calls = 0
        lateinit var unregister: () -> Unit
        unregister = scrubber.register {
            calls++
            unregister()
        }
        scrubber.scrub(hidden = true)
        scrubber.scrub(hidden = true)

        assertEquals(1, calls)
    }
}
