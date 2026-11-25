package org.openlife.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * A-13 (P1-19-R16): OcrViewModel subscribed to the visible-source list of
 * the vault that was Ready when it was created, once. A vault that opened
 * later (Unavailable then Ready) or was reset was never watched, so OCR text
 * for a source deleted afterwards stayed in memory. The tracker follows
 * whichever vault is current.
 */
class DeletedSourceTrackerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val forgotten = mutableListOf<Set<UUID>>()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun forgetsSourcesDeletedFromAVaultThatOpenedOrWasResetLater() {
        val vaults = MutableStateFlow<Flow<Set<UUID>>?>(null)
        scope.launch { forgetSourcesLeavingTheList(vaults) { forgotten += it } }

        // The vault opens after the ViewModel was created (Unavailable -> Ready).
        val first = MutableStateFlow<Set<UUID>>(emptySet())
        vaults.value = first
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        first.value = setOf(a, b)
        first.value = setOf(b)
        assertEquals(listOf(setOf(a)), forgotten)

        // Reset: a new vault replaces the old one; the new list is watched too.
        vaults.value = null
        val second = MutableStateFlow<Set<UUID>>(emptySet())
        vaults.value = second
        val c = UUID.randomUUID()
        second.value = setOf(c)
        forgotten.clear()
        second.value = emptySet()
        assertEquals(listOf(setOf(c)), forgotten)

        // The replaced vault's flow is no longer followed.
        forgotten.clear()
        first.value = emptySet()
        assertEquals(emptyList<Set<UUID>>(), forgotten)
    }
}
