package org.openlife.vault.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.facts.extract.ExtractedKind

/**
 * P3-04: mapKind covers every extracted kind, and each kind keeps its name in the stored fact kind. The stored kind is
 * the key the duplicate and continuation rules compare, so a rename there would silently create duplicates.
 */
class MapKindTest {
    @Test
    fun mapKindCoversEveryExtractedKindWithTheSameName() {
        for (kind in ExtractedKind.entries) {
            assertEquals("kind ${kind.name} maps to the stored kind of the same name", kind.name, mapKind(kind).name)
        }
    }
}
