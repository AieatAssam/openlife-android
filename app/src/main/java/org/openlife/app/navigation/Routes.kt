package org.openlife.app.navigation

import kotlinx.serialization.Serializable

/** Typed navigation arguments are deliberately limited to UUID strings. */
sealed interface Routes {
    @Serializable
    data object List : Routes

    /** [spanId] (P2-08): the span to select on opening, from a search result. */
    @Serializable
    data class Viewer(val sourceId: String, val spanId: String? = null) : Routes

    /** P2-08: local search. The query is never a route argument. */
    @Serializable
    data object Search : Routes

    @Serializable
    data object Settings : Routes

    @Serializable
    data object About : Routes
}
