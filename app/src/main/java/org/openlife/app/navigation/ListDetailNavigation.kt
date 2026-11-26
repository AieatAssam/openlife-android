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
 * Because the selection is in the route, a resize between compact and
 * expanded changes only [panes], never the back stack, and Back pops one
 * entry: from a detail at compact width that returns to the list; at
 * expanded width it clears the selection, and the next Back leaves.
 */
object ListDetailNavigation {
    fun panes(selectedSourceId: String?, expanded: Boolean): ListDetailPanes = when {
        selectedSourceId == null && expanded -> ListDetailPanes.LIST_AND_PLACEHOLDER
        selectedSourceId == null -> ListDetailPanes.LIST_ONLY
        expanded -> ListDetailPanes.LIST_AND_DETAIL
        else -> ListDetailPanes.DETAIL_ONLY
    }

    /**
     * Opening a source: from the list, a new detail entry; from a detail (the
     * list pane beside it), the selection is replaced, so Back still clears
     * it in one step; from search at expanded width the result is shown
     * beside the list (P2-08), at compact width over the results.
     */
    fun select(top: TopDestination, expanded: Boolean): SelectAction = when (top) {
        TopDestination.LIST -> SelectAction.PUSH
        TopDestination.LIST_DETAIL -> SelectAction.REPLACE_TOP
        TopDestination.SEARCH -> if (expanded) SelectAction.POP_TO_LIST_THEN_PUSH else SelectAction.PUSH
        TopDestination.OTHER -> SelectAction.PUSH
    }
}
