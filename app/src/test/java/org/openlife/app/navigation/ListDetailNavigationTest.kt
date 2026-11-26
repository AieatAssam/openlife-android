package org.openlife.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-20-R3 (AS-3), JVM part of AdaptiveLayoutTest.selectionSurvivesCompactToExpandedAndBack
 * and expandedBackClearsSelectionBeforeLeaving: the selection is a route
 * argument, so a resize changes only which panes are drawn, never the back
 * stack. The stack here is applied exactly as OpenLifeNavHost applies
 * [ListDetailNavigation.select]'s actions; Back pops one entry, as NavHost does.
 * Also replaces the source-grep unit NavigationBackTest (A-09).
 */
class ListDetailNavigationTest {
    private val a = "00000000-0000-0000-0000-00000000000a"
    private val b = "00000000-0000-0000-0000-00000000000b"

    @Test
    fun selectionSurvivesCompactToExpandedAndBack() {
        val stack = open(listOf(Routes.List), a, expanded = false)

        assertEquals(listOf(Routes.List, Routes.ListDetail(a)), stack)
        assertEquals(ListDetailPanes.DETAIL_ONLY, panes(stack, expanded = false))
        // Unfold, rotate or widen the window: same stack, two panes, same selection.
        assertEquals(ListDetailPanes.LIST_AND_DETAIL, panes(stack, expanded = true))
        assertEquals(ListDetailPanes.DETAIL_ONLY, panes(stack, expanded = false))
        assertEquals(a, (stack.last() as Routes.ListDetail).sourceId)
    }

    @Test
    fun compactBackFromDetailReturnsToTheList() {
        val stack = back(open(listOf(Routes.List), a, expanded = false))

        assertEquals(listOf(Routes.List), stack)
        assertEquals(ListDetailPanes.LIST_ONLY, panes(stack, expanded = false))
    }

    @Test
    fun expandedBackClearsSelectionBeforeLeaving() {
        val selected = open(listOf(Routes.List), a, expanded = true)
        assertEquals(ListDetailPanes.LIST_AND_DETAIL, panes(selected, expanded = true))

        val cleared = back(selected)
        assertEquals(listOf(Routes.List), cleared)
        assertEquals(ListDetailPanes.LIST_AND_PLACEHOLDER, panes(cleared, expanded = true))

        assertTrue("the next Back leaves the app", back(cleared).isEmpty())
    }

    @Test
    fun expandedSelectingAnotherItemReplacesTheSelection() {
        val first = open(listOf(Routes.List), a, expanded = true)
        val second = open(first, b, expanded = true)

        assertEquals(listOf(Routes.List, Routes.ListDetail(b)), second)
        assertEquals(listOf(Routes.List), back(second))
    }

    @Test
    fun compactSearchResultOpensOverSearchAndBackReturnsToResults() {
        val stack = open(listOf(Routes.List, Routes.Search), a, expanded = false, spanId = b)

        assertEquals(listOf(Routes.List, Routes.Search, Routes.ListDetail(a, b)), stack)
        assertEquals(listOf(Routes.List, Routes.Search), back(stack))
    }

    @Test
    fun expandedSearchResultReturnsToTheListWithTheSelection() {
        val stack = open(listOf(Routes.List, Routes.Search), a, expanded = true, spanId = b)

        assertEquals(listOf(Routes.List, Routes.ListDetail(a, b)), stack)
        assertEquals(ListDetailPanes.LIST_AND_DETAIL, panes(stack, expanded = true))
    }

    @Test
    fun anInternalLinkOverAnotherScreenPushesTheDetail() {
        val stack = open(listOf(Routes.List, Routes.Settings), a, expanded = false)

        assertEquals(listOf(Routes.List, Routes.Settings, Routes.ListDetail(a)), stack)
    }

    @Test
    fun theListWithNothingSelectedShowsAPlaceholderOnlyWhenExpanded() {
        assertEquals(ListDetailPanes.LIST_ONLY, ListDetailNavigation.panes(null, expanded = false))
        assertEquals(ListDetailPanes.LIST_AND_PLACEHOLDER, ListDetailNavigation.panes(null, expanded = true))
    }

    private fun open(stack: List<Routes>, id: String, expanded: Boolean, spanId: String? = null): List<Routes> {
        val target = Routes.ListDetail(id, spanId)
        return when (ListDetailNavigation.select(topOf(stack), expanded)) {
            SelectAction.PUSH -> stack + target
            SelectAction.REPLACE_TOP -> stack.dropLast(1) + target
            SelectAction.POP_TO_LIST_THEN_PUSH -> stack.take(stack.lastIndexOf(Routes.List) + 1) + target
        }
    }

    private fun topOf(stack: List<Routes>): TopDestination = when (stack.last()) {
        Routes.List -> TopDestination.LIST
        is Routes.ListDetail -> TopDestination.LIST_DETAIL
        Routes.Search -> TopDestination.SEARCH
        else -> TopDestination.OTHER
    }

    private fun back(stack: List<Routes>): List<Routes> = stack.dropLast(1)

    private fun panes(stack: List<Routes>, expanded: Boolean): ListDetailPanes =
        ListDetailNavigation.panes((stack.last() as? Routes.ListDetail)?.sourceId, expanded)
}
