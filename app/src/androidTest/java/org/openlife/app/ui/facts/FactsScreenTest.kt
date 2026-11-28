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

    private val evidence = FactEvidence(EvidenceRole.VALUE, READING.toString(), SPAN.toString(), 4, 14)

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
                    state = FactsUiState.Ready(items, ExtractionCheck.Completed, currentReadingId = READING),
                    actions = FactActions(
                        onOpenEvidence = { opened = it },
                        onReview = { _, _ -> },
                        onChoose = { _, _ -> },
                    ),
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

    /** P3-05 review U-04: a value read through a user's correction says so; an unrecognised origin or status is named unknown. */
    @Test
    fun originFromCorrectionAndUnrecognisedLabels() {
        val corrected = FactItem(
            factId = UUID.randomUUID(),
            revisionId = UUID.randomUUID(),
            kind = FactKind.DATE,
            origin = FactOrigin.EXTRACTED,
            status = FactStatus.CONFIRMED,
            staleReason = null,
            value = FactValue("{\"date\":\"2026-04-03\"}", "2026-04-03"),
            candidateTexts = emptyList(),
            evidence = listOf(evidence.copy(ocrUserRevisionId = CORRECTION.toString())),
        )
        val unknown = FactItem(
            factId = UUID.randomUUID(),
            revisionId = UUID.randomUUID(),
            kind = FactKind.DATE,
            origin = FactOrigin.UNRECOGNISED,
            status = FactStatus.UNRECOGNISED,
            staleReason = null,
            value = FactValue("{\"date\":\"2026-06-01\"}", "2026-06-01"),
            candidateTexts = emptyList(),
        )
        composeRule.setContent {
            OpenLifeTheme {
                FactsPanel(
                    state = FactsUiState.Ready(listOf(corrected, unknown), ExtractionCheck.Completed, READING),
                    actions = FactActions(onOpenEvidence = { _ -> }, onReview = { _, _ -> }, onChoose = { _, _ -> }),
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.facts_origin_from_correction)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.facts_origin_unknown)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.facts_status_unknown)).assertIsDisplayed()
    }

    private companion object {
        val CORRECTION: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val READING: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val SPAN: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
