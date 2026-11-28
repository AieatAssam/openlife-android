package org.openlife.vault.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openlife.vault.storage.FactEvidenceEntity

/** P3-05-R1: the domain types the facts UI reads, mapped from the DAO's raw columns. No SQL strings leave the vault. */
class FactReadMappingTest {
    private fun evidence(role: String, textStart: Int?, userRevision: String? = null) = FactEvidenceEntity(
        id = "e1",
        revisionId = "x",
        ordinal = 0,
        role = role,
        sourceId = "a",
        ocrRevisionId = "a-r",
        ocrSpanId = "a-s1",
        ocrUserRevisionId = userRevision,
        textStart = textStart,
        textEnd = textStart?.plus(10),
        regionLeft = null,
        regionTop = null,
        regionRight = null,
        regionBottom = null,
        regionCoordinateSystem = null,
    )

    @Test
    fun evidenceKeepsTheReadingSpanAndTextRange() {
        val mapped = evidence("VALUE", 4).toFactEvidence()
        assertEquals(EvidenceRole.VALUE, mapped.role)
        assertEquals("a-r", mapped.ocrRevisionId)
        assertEquals("a-s1", mapped.ocrSpanId)
        assertEquals(4 to 14, mapped.textStart to mapped.textEnd)
    }

    /** P3-05 review U-04: the correction a value was read through reaches the domain evidence. */
    @Test
    fun evidenceKeepsTheCorrectionItWasReadFrom() {
        assertEquals("u1", evidence("VALUE", 4, userRevision = "u1").toFactEvidence().ocrUserRevisionId)
        assertNull(evidence("VALUE", 4).toFactEvidence().ocrUserRevisionId)
    }

    @Test
    fun anUnknownEvidenceRoleIsRefusedNotGuessed() {
        val failure = runCatching { evidence("FUTURE_ROLE", 0).toFactEvidence() }
        assertEquals(true, failure.isFailure)
    }

    @Test
    fun noRunForTheCurrentReadingIsNotChecked() {
        assertEquals(ExtractionCheck.NotChecked, null.toExtractionCheck())
    }

    @Test
    fun aCompletedRunIsCompletedAndAnyOtherOutcomeIsFailed() {
        assertEquals(ExtractionCheck.Completed, "COMPLETED".toExtractionCheck())
        assertEquals(ExtractionCheck.Failed(ExtractionOutcome.SOURCE_NOT_READY), "SOURCE_NOT_READY".toExtractionCheck())
    }

    @Test
    fun aDecisionStringMapsToItsEnum() {
        assertNull(null.toReviewDecision())
        assertEquals(ReviewDecision.REOPEN, "REOPEN".toReviewDecision())
    }
}
