package org.openlife.app.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

/**
 * P1-19-R16 (A-13): calls [forget] with the ids that leave the visible list
 * of whichever vault is current. [vaults] emits the current vault's
 * visible-id flow, or null while no vault is Ready; a vault that opens late
 * or replaces a reset one is followed from its first emission, and the
 * previous vault's flow is dropped.
 */
internal suspend fun forgetSourcesLeavingTheList(vaults: Flow<Flow<Set<UUID>>?>, forget: (Set<UUID>) -> Unit) {
    vaults.collectLatest { visibleIds ->
        var previous = emptySet<UUID>()
        visibleIds?.collect { visible ->
            val gone = previous - visible
            if (gone.isNotEmpty()) forget(gone)
            previous = visible
        }
    }
}
