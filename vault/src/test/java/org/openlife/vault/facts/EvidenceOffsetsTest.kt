package org.openlife.vault.facts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C2 §4.3 / P3-02 review M-03: the upper bound `textEnd ≤ length` is checked
 * in Kotlin, in UTF-16 units, because SQLite's `length()` counts code points.
 * The trigger checks the rest (set for VALUE, `0 ≤ start < end`).
 */
class EvidenceOffsetsTest {
    @Test
    fun offsetsAreUtf16UnitsWithinTheReadText() {
        val text = "💶12" // a banknote emoji then "12": 4 UTF-16 units, 3 code points
        assertEquals(true, EvidenceOffsets.fit(text, 0, 4))
        assertEquals(true, EvidenceOffsets.fit(text, 2, 4))
        assertEquals(false, EvidenceOffsets.fit(text, 0, 5))
        assertEquals(false, EvidenceOffsets.fit(text, 2, 2))
        assertEquals(false, EvidenceOffsets.fit(text, -1, 2))
    }
}
