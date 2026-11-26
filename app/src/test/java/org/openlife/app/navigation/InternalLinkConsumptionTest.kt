package org.openlife.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * Review N-04: an internal link is opened where the window width is known,
 * so at expanded width it goes back to the list before showing the source
 * (over Search, too), and at compact width it is pushed.
 */
class InternalLinkConsumptionTest {
    @Test
    fun theDecisionDependsOnTheWidthForEveryTopDestination() {
        assertEquals(
            SelectAction.POP_TO_LIST_THEN_PUSH,
            ListDetailNavigation.select(TopDestination.SEARCH, expanded = true),
        )
        assertEquals(SelectAction.PUSH, ListDetailNavigation.select(TopDestination.SEARCH, expanded = false))
        assertEquals(SelectAction.REPLACE_TOP, ListDetailNavigation.select(TopDestination.LIST_DETAIL, expanded = true))
        assertEquals(SelectAction.PUSH, ListDetailNavigation.select(TopDestination.LIST, expanded = false))
    }

    /** Source scan: the activity, which does not know the width, no longer opens links itself. */
    @Test
    fun theLinkIsConsumedInsideTheNavHostNotTheActivity() {
        val activity = Paths.get("src/main/java/org/openlife/app/MainActivity.kt").readText()
        val host = Paths.get("src/main/java/org/openlife/app/navigation/OpenLifeNavHost.kt").readText()

        assertFalse("MainActivity opens a source with a guessed width", activity.contains("openSource("))
        assertTrue("OpenLifeNavHost takes pending internal links", host.contains("internalLinks.take()"))
    }
}
