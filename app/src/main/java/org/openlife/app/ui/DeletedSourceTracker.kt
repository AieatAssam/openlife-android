package org.openlife.app.ui

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** RED stub (P1-19-R16). */
internal suspend fun forgetSourcesLeavingTheList(vaults: Flow<Flow<Set<UUID>>?>, forget: (Set<UUID>) -> Unit): Unit =
    TODO("P1-19-R16 $vaults $forget")
