package org.openlife.app.navigation

import kotlinx.serialization.Serializable

/** Typed navigation arguments are deliberately limited to UUID strings. */
sealed interface Routes {
    @Serializable
    data object List : Routes

    /**
     * P1-20-R3: the list-detail destination; the selected source is part of the
     * route. [spanId] (P2-08): the span to select on opening, from a search result.
     */
    @Serializable
    data class ListDetail(val sourceId: String, val spanId: String? = null) : Routes

    /** P2-08: local search. The query is never a route argument. */
    @Serializable
    data object Search : Routes

    /** P3-05-R1: the facts of one source. The source id is the only argument; no fact value is in the route. */
    @Serializable
    data class Facts(val sourceId: String) : Routes

    @Serializable
    data object Settings : Routes

    @Serializable
    data object About : Routes
}
