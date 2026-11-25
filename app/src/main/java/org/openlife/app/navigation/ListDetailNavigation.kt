package org.openlife.app.navigation

/** What a list-detail entry shows (P1-20-R3); the route and the width decide, nothing navigates on resize. */
enum class ListDetailPanes { LIST_ONLY, DETAIL_ONLY, LIST_AND_PLACEHOLDER, LIST_AND_DETAIL }

/** How opening a source changes the back stack. */
enum class SelectAction { PUSH, REPLACE_TOP, POP_TO_LIST_THEN_PUSH }

/** The kind of entry on top of the back stack when a source is opened. */
enum class TopDestination { LIST, LIST_DETAIL, SEARCH, OTHER }

/**
 * P1-20-R3 (AS-3): pure back-stack and pane rules for the list-detail
 * destination. The selected source id is a route argument of
 * [Routes.ListDetail]; [Routes.List] is the list with nothing selected.
 */
object ListDetailNavigation {
    fun panes(selectedSourceId: String?, expanded: Boolean): ListDetailPanes = TODO()

    fun select(top: TopDestination, expanded: Boolean): SelectAction = TODO()
}
