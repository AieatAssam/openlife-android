package org.openlife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review N-02: the list ViewModel keeps two ints, not a LazyListState. */
class ListScrollPositionTest {
    @Test
    fun startsAtTheTopAndKeepsTheLastSavedPosition() {
        val position = ListScrollPosition()
        assertEquals(0 to 0, position.index to position.offset)

        position.save(12, 40)

        assertEquals(12 to 40, position.index to position.offset)
    }

    @Test
    fun negativeValuesAreClampedToTheTop() {
        val position = ListScrollPosition()

        position.save(-1, -5)

        assertEquals(0 to 0, position.index to position.offset)
    }
}
