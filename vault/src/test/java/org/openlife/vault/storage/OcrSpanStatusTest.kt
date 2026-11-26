package org.openlife.vault.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.vault.facts.SpanEligibility
import org.openlife.vault.facts.SpanEligibilityInput
import org.openlife.vault.ocr.OcrCoordinateSystem
import org.openlife.vault.ocr.OcrEvidenceRegion
import org.openlife.vault.ocr.OcrSpan
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import java.util.UUID

/**
 * VS-3, C2-R16/R17: every span persists `hasEvidenceRegion` and
 * `scriptStatus`, so accepting a reading never makes an ungrounded or
 * out-of-script span eligible (C2 §1).
 */
class OcrSpanStatusTest {
    private val hosts = mutableListOf<HostSqlite>()

    @After
    fun close() = hosts.forEach(HostSqlite::close)

    private val accepted = SpanEligibilityInput(
        readingState = "READY",
        readingReviewState = "ACCEPTED",
        readingHasWholeCorrection = false,
        hasEvidenceRegion = true,
        latestSpanReview = "ACCEPTED",
        storedScriptStatus = "LATIN",
        currentCorrectionText = null,
    )

    @Test
    fun acceptingARevisionDoesNotAcceptUngroundedOrOutOfScriptSpans() {
        assertTrue(SpanEligibility.isEligible(accepted))
        assertFalse("no line box", SpanEligibility.isEligible(accepted.copy(hasEvidenceRegion = false)))
        assertFalse("out of script", SpanEligibility.isEligible(accepted.copy(storedScriptStatus = "UNSUPPORTED")))
        assertFalse("never classified", SpanEligibility.isEligible(accepted.copy(storedScriptStatus = "UNKNOWN")))
        assertFalse("a correction is classified as read", SpanEligibility.isEligible(accepted.copy(currentCorrectionText = "Итого")))
        assertTrue(SpanEligibility.isEligible(accepted.copy(storedScriptStatus = "UNSUPPORTED", currentCorrectionText = "Total")))
        assertFalse(SpanEligibility.isEligible(accepted.copy(latestSpanReview = "REJECTED")))
        assertFalse(SpanEligibility.isEligible(accepted.copy(readingReviewState = "REJECTED")))
        assertFalse(SpanEligibility.isEligible(accepted.copy(readingHasWholeCorrection = true)))
        assertFalse(SpanEligibility.isEligible(accepted.copy(readingState = "RUNNING")))

        val boxless = OcrSpan(UUID.randomUUID(), UUID.randomUUID(), 0, "Итого", null, OcrCoordinateSystem.SOURCE_PIXELS, null)
        val boxed = boxless.copy(text = "Total", evidenceRegion = OcrEvidenceRegion(1, 1, 5, 5))
        assertEquals("UNSUPPORTED", spanScriptStatusOf(boxless.text))
        assertEquals("LATIN", spanScriptStatusOf(boxed.text))
        assertEquals("LATIN", spanScriptStatusOf("12.50 €"))
    }

    @Test
    fun aClassifierFailureRollsTheWholeMigrationBack() {
        val host = HostSqlite().also { hosts += it }
        host.createFromExportedSchema(FactHost.schema(5))
        val fixture = org.openlife.vault.search.SearchFixture(host)
        fixture.source("a")
        fixture.revision("a-r", "a")
        fixture.span("a-s1", "a-r", 0, "Total")
        val db = host.asSupportDatabase()
        val before = host.query("SELECT name, sql FROM sqlite_master ORDER BY name")
        db.beginTransaction()
        try {
            FactMigration.narrowFtsUpdateTriggers(db)
            FactMigration.addColumns(db)
            FactMigration.backfill(db) { error("classifier failure") }
            db.setTransactionSuccessful()
            fail("the migration must throw")
        } catch (expected: IllegalStateException) {
            assertEquals("classifier failure", expected.message)
        } finally {
            db.endTransaction()
        }
        assertEquals("nothing is half-migrated", before, host.query("SELECT name, sql FROM sqlite_master ORDER BY name"))
    }
}
