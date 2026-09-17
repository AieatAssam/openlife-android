package org.openlife.app.navigation

import kotlinx.serialization.Serializable

/** Typed navigation arguments are deliberately limited to UUID strings. */
sealed interface Routes {
    @Serializable
    data object List : Routes

    @Serializable
    data class Viewer(val sourceId: String) : Routes

    @Serializable
    data object Settings : Routes

    @Serializable
    data object About : Routes
}
