package org.openlife.vault.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** VS-3, C2-R14/R15 on host SQLite: one current reading per Source; corrections supersede explicitly. */
class OcrRevisionPointerTest {
    private val host = HostSqlite()
    private val rows = FactRows(host)

    @After
    fun close() = host.close()

    private fun refused(requirement: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal citing $requirement")
        } catch (e: HostSqlite.Failure) {
            assertTrue("${e.message} should cite $requirement", e.message.orEmpty().contains(requirement))
        }
    }

    private fun current(sourceId: String) =
        host.query("SELECT currentOcrRevisionId FROM sources WHERE id = ?", sourceId).single().single()

    private fun head(revisionId: String, spanId: String?) = with(FactHost) {
        host.named(OcrPointerSql.CURRENT_CORRECTION, "revisionId" to revisionId, "spanId" to spanId)
    }.map { it.single() }

    @Test
    fun exactlyOneCurrentRevisionPerSourceAndCorrectionsSupersedeExplicitly() {
        val fixture = FactHost.fresh(host)
        fixture.source("a")
        fixture.source("b")
        fixture.revision("r1", "a")
        fixture.revision("late", "a", state = "RUNNING")
        fixture.revision("r2", "a")
        fixture.revision("b1", "b")
        fixture.revision("f1", "a", state = "FAILED")
        assertEquals(null, current("a"))
        assertEquals(1, rows.moveCurrentReading("a", "r2"))
        assertEquals("r2", current("a"))
        assertEquals("an older reading never becomes current", 0, rows.moveCurrentReading("a", "r1"))
        host.exec("UPDATE ocr_revisions SET state = 'READY' WHERE id = 'late'")
        assertEquals(
            "a late finisher that started earlier stays history (F-69)",
            0,
            rows.moveCurrentReading("a", "late"),
        )
        assertEquals("r2", current("a"))
        refused("C2-R14") { host.exec("UPDATE sources SET currentOcrRevisionId = 'b1' WHERE id = 'a'") }
        refused("C2-R14") { host.exec("UPDATE sources SET currentOcrRevisionId = 'f1' WHERE id = 'a'") }
        refused("C2-R14") { host.exec("UPDATE sources SET currentOcrRevisionId = 'r1' WHERE id = 'a'") }

        fixture.span("s1", "r2", 0, "Due 26.11.2026")
        assertEquals(emptyList<Any?>(), head("r2", "s1"))
        rows.correction("c1", "r2", "s1", "one", null)
        refused("C2-R15") { rows.correction("cx", "r2", "s1", "two", null) }
        rows.correction("c2", "r2", "s1", "two", "c1")
        refused("C2-R15") { rows.correction("cy", "r2", "s1", "three", "c1") }
        refused("C2-R15") { rows.correction("cz", "r2", null, "whole", "c2") }
        assertEquals(listOf<Any?>("c2"), head("r2", "s1"))
        rows.correction("w1", "r2", null, "whole", null)
        assertEquals(listOf<Any?>("w1"), head("r2", null))
        refused("C2-R15") { host.exec("UPDATE ocr_user_revisions SET supersedesId = NULL WHERE id = 'c2'") }
    }

    /**
     * S-04 / F-71 (C2-R14 amendment 2026-11-28, S-01): the device clock is set back, so a later
     * reading carries an earlier startedAt. It must still become current and be listed first.
     */
    @Test
    fun aReadingWithAnEarlierStartedAtStillBecomesCurrentAndIsListedFirst() {
        val fixture = FactHost.fresh(host)
        fixture.source("c")
        fixture.revision("before-clock-change", "c")
        assertEquals(1, rows.moveCurrentReading("c", "before-clock-change"))
        fixture.revision("after-clock-change", "c")
        host.exec("UPDATE ocr_revisions SET startedAt = 1 WHERE id = 'after-clock-change'")
        assertEquals(
            "a reading with a greater rowid moves the pointer even when its startedAt is earlier (F-71)",
            1,
            rows.moveCurrentReading("c", "after-clock-change"),
        )
        assertEquals("after-clock-change", current("c"))
        val newestFirst = host.query("SELECT id FROM ocr_revisions WHERE sourceId = ? ORDER BY rowid DESC", "c")
        assertEquals("the viewer order (rowid DESC) lists the new reading first", "after-clock-change", newestFirst.first().single())
    }
}
