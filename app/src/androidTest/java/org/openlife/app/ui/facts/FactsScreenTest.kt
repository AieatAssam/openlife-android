package org.openlife.app.ui.facts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.openlife.app.R
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.facts.EvidenceRole
import org.openlife.vault.facts.ExtractionCheck
import org.openlife.vault.facts.FactEvidence
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import java.util.UUID

/** P3-05-R1 through the panel: origin and status are named in text, and "Show in the text" reports its evidence. */
class FactsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val evidence = FactEvidence(EvidenceRole.VALUE, "a-r", "a-s1", 4, 14)

    @Test
    fun showsOriginAndStatusBadgesAndEvidenceNavigation() {
        var opened: FactEvidence? = null
        val items = listOf(
            FactItem(
                factId = UUID.randomUUID(),
                revisionId = UUID.randomUUID(),
                kind = FactKind.DATE,
                origin = FactOrigin.EXTRACTED,
                status = FactStatus.CONFIRMED,
                staleReason = null,
                value = FactValue("{\"date\":\"2026-04-03\"}", "2026-04-03"),
                candidateTexts = emptyList(),
                evidence = listOf(evidence),
            ),
            FactItem(
                factId = UUID.randomUUID(),
                revisionId = UUID.randomUUID(),
                kind = FactKind.DATE,
                origin = FactOrigin.USER,
                status = FactStatus.STALE,
                staleReason = null,
                value = FactValue("{\"date\":\"2026-05-01\"}", "2026-05-01"),
                candidateTexts = emptyList(),
            ),
        )
        composeRule.setContent {
            OpenLifeTheme {
                FactsPanel(
                    state = FactsUiState.Ready(items, ExtractionCheck.Completed),
                    onOpenEvidence = { opened = it },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.facts_origin_extracted)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.facts_origin_user)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.facts_status_confirmed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.facts_status_stale)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.facts_show_evidence)).onFirst().performClick()
        assertEquals(evidence, opened)
    }
}
