package org.openlife.vault.storage

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The C2 triggers and the C2 CHECK constraints (docs/capabilities/C2.md §4),
 * which Room cannot declare on an entity. One definition, used by
 * [OpenLifeDatabase.readyInvariantCallback] for a fresh database and by
 * [FactMigration] for a migrated one, so both end with the same triggers
 * (FactDatabaseTest). Idempotent.
 *
 * Every refusal is `RAISE(ABORT, '<requirement>: <rule>')`; the message names
 * a rule, never a value. The repositories check the same rules first; these
 * are the second, independent layer (C2-R7).
 *
 * A guard that allows a delete "while the Source is DELETING" (C2-R20) looks
 * the Source up by its row: once the `sources` row is gone the pointer that
 * named the target is gone too, so nothing is left to protect.
 *
 * No statement here uses a CTE, so the SQL is the same on any SQLite or
 * SQLCipher build the vault ships with.
 */
object FactSchemaTriggers {
    private const val STATUSES = "('UNREVIEWED', 'CONFIRMED', 'REJECTED', 'CLARIFICATION_NEEDED', 'STALE')"
    private const val STALE_REASONS =
        "('READING_REPLACED', 'READING_REJECTED', 'SPAN_CORRECTED', 'READING_CORRECTED', 'SPAN_REJECTED', 'BASIS_STALE')"
    private const val KINDS = "('DATE', 'TIME', 'DATETIME', 'MONEY', 'URL', 'EMAIL', 'PHONE')"
    private const val OUTCOMES = "('COMPLETED', 'SOURCE_NOT_READY', 'NO_CURRENT_READING', 'READING_REJECTED', " +
        "'READING_CORRECTED_AS_A_WHOLE', 'LIMIT_EXCEEDED', 'TIMEOUT', 'STORAGE_FAILURE')"

    private fun raise(rule: String, condition: String) = "SELECT RAISE(ABORT, '$rule') WHERE $condition;"

    /** The stale columns agree with the status (C2 §4.2): set iff STALE, with a known reason. */
    private fun staleColumnsAgree(rule: String) = raise(
        rule,
        "(NEW.staleReason IS NULL) <> (NEW.staleAt IS NULL) OR (NEW.status = 'STALE') <> " +
            "(NEW.staleReason IS NOT NULL) " +
            "OR (NEW.staleReason IS NOT NULL AND NEW.staleReason NOT IN $STALE_REASONS)",
    )

    private val triggers: List<Pair<String, String>> = listOf(
        // facts (C2-R3)
        "c2_facts_insert" to "BEFORE INSERT ON facts BEGIN " +
            raise("C2-R3: a fact ordinal starts at 1", "NEW.ordinal < 1") +
            raise("C2-R3: a fact is inserted before its first revision", "NEW.currentRevisionId IS NOT NULL") +
            raise("C2 §4.1: unknown fact kind", "NEW.kind NOT IN $KINDS") +
            " END",
        "c2_facts_immutable" to "BEFORE UPDATE OF id, sourceId, kind, ordinal, createdAt ON facts " +
            "WHEN NEW.id IS NOT OLD.id OR NEW.sourceId IS NOT OLD.sourceId OR NEW.kind IS NOT OLD.kind " +
            "OR NEW.ordinal IS NOT OLD.ordinal OR NEW.createdAt IS NOT OLD.createdAt BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R3: a fact is immutable except its current revision'); END",
        "c2_facts_current_revision" to "BEFORE UPDATE OF currentRevisionId ON facts " +
            "WHEN NEW.currentRevisionId IS NOT OLD.currentRevisionId BEGIN " +
            raise("C2-R3: the current revision never returns to NULL", "NEW.currentRevisionId IS NULL") +
            raise(
                "C2-R3: the current revision is a revision of the fact",
                "NOT EXISTS (SELECT 1 FROM fact_revisions WHERE id = NEW.currentRevisionId AND factId = NEW.id)",
            ) +
            " END",
        // fact_revisions (C2 §4.2, C2-R4, C2-R6, C2-R7, C2-R20)
        "c2_fact_revisions_insert" to "BEFORE INSERT ON fact_revisions BEGIN " +
            raise("C2-R7: origin is EXTRACTED or USER", "NEW.origin NOT IN ('EXTRACTED', 'USER')") +
            raise("C2-R6: confidence is NULL", "NEW.confidence IS NOT NULL") +
            raise("C2 §4.2: valueJson is at most 4096 bytes", "length(CAST(NEW.valueJson AS BLOB)) > 4096") +
            raise("C2 §4.2: revisionNo starts at 1", "NEW.revisionNo < 1") +
            raise("C2 §4.2: unknown status", "NEW.status NOT IN $STATUSES") +
            staleColumnsAgree("C2 §4.2: staleReason and staleAt are set iff STALE") +
            raise(
                "C2-R7: a USER revision has no rawText, methodVersion or locale",
                "NEW.origin = 'USER' AND (NEW.rawText IS NOT NULL OR NEW.methodVersion IS NOT NULL " +
                    "OR NEW.locale IS NOT NULL)",
            ) +
            raise(
                "C2-R7: an EXTRACTED revision is confirmed only after its VALUE evidence",
                "NEW.origin = 'EXTRACTED' AND NEW.status = 'CONFIRMED'",
            ) +
            raise(
                "C2 §4.2: a revision has the kind of its fact",
                "NEW.kind IS NOT (SELECT kind FROM facts WHERE id = NEW.factId)",
            ) +
            raise(
                "C2 §4.2: a revision supersedes a revision of the same fact",
                "NEW.supersedesRevisionId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM fact_revisions " +
                    "WHERE id = NEW.supersedesRevisionId AND factId = NEW.factId)",
            ) +
            " END",
        "c2_fact_revisions_immutable" to "BEFORE UPDATE OF id, factId, revisionNo, origin, kind, valueJson, " +
            "canonicalText, rawText, confidence, methodId, methodVersion, locale, createdAt, supersedesRevisionId " +
            "ON fact_revisions WHEN NEW.id IS NOT OLD.id OR NEW.factId IS NOT OLD.factId " +
            "OR NEW.revisionNo IS NOT OLD.revisionNo OR NEW.origin IS NOT OLD.origin OR NEW.kind IS NOT OLD.kind " +
            "OR NEW.valueJson IS NOT OLD.valueJson OR NEW.canonicalText IS NOT OLD.canonicalText " +
            "OR NEW.rawText IS NOT OLD.rawText OR NEW.confidence IS NOT OLD.confidence " +
            "OR NEW.methodId IS NOT OLD.methodId OR NEW.methodVersion IS NOT OLD.methodVersion " +
            "OR NEW.locale IS NOT OLD.locale OR NEW.createdAt IS NOT OLD.createdAt " +
            "OR NEW.supersedesRevisionId IS NOT OLD.supersedesRevisionId BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R4: a revision value is immutable'); END",
        "c2_fact_revisions_status" to "BEFORE UPDATE OF status, staleReason, staleAt ON fact_revisions BEGIN " +
            raise(
                "C2 §5: STALE is terminal",
                "OLD.status = 'STALE' AND (NEW.status IS NOT OLD.status OR NEW.staleReason IS NOT OLD.staleReason " +
                    "OR NEW.staleAt IS NOT OLD.staleAt)",
            ) +
            raise("C2 §4.2: unknown status", "NEW.status NOT IN $STATUSES") +
            staleColumnsAgree("C2 §4.2: staleReason and staleAt are set iff STALE") +
            raise(
                "C2-R23: a candidates value is never CONFIRMED",
                "NEW.status = 'CONFIRMED' AND NEW.valueJson LIKE '{\"candidates\":%'",
            ) +
            raise(
                "C2-R7: an EXTRACTED revision is confirmed only with a VALUE evidence row",
                "NEW.status = 'CONFIRMED' AND OLD.status IS NOT 'CONFIRMED' AND NEW.origin = 'EXTRACTED' " +
                    "AND NOT EXISTS (SELECT 1 FROM fact_evidence WHERE revisionId = NEW.id AND role = 'VALUE')",
            ) +
            " END",
        "c2_fact_revisions_basis" to "AFTER INSERT ON fact_revisions " +
            "WHEN NEW.origin = 'USER' AND NEW.supersedesRevisionId IS NOT NULL BEGIN " +
            "INSERT INTO dependency_edges(edgeKind, dependentKind, dependentId, dependentSourceId, dependencyKind, " +
            "dependencyId, dependencySourceId) SELECT 'BASIS', 'FACT_REVISION', NEW.id, f.sourceId, " +
            "'FACT_REVISION', p.id, f.sourceId FROM fact_revisions p JOIN facts f ON f.id = p.factId " +
            "WHERE p.id = NEW.supersedesRevisionId AND p.origin <> 'USER'; " +
            "INSERT INTO dependency_edges(edgeKind, dependentKind, dependentId, dependentSourceId, dependencyKind, " +
            "dependencyId, dependencySourceId) SELECT 'BASIS', 'FACT_REVISION', NEW.id, e.dependentSourceId, " +
            "e.dependencyKind, e.dependencyId, e.dependencySourceId FROM dependency_edges e " +
            "JOIN fact_revisions p ON p.id = e.dependentId WHERE e.edgeKind = 'BASIS' " +
            "AND e.dependentKind = 'FACT_REVISION' AND e.dependentId = NEW.supersedesRevisionId " +
            "AND p.origin = 'USER'; END",
        "c2_fact_revisions_delete_guard" to "BEFORE DELETE ON fact_revisions " +
            "WHEN EXISTS (SELECT 1 FROM facts WHERE id = OLD.factId AND currentRevisionId = OLD.id) " +
            "AND NOT EXISTS (SELECT 1 FROM facts f JOIN sources s ON s.id = f.sourceId " +
            "WHERE f.id = OLD.factId AND s.state = 'DELETING') BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R20: a current revision is deleted only with its Source'); END",
        "c2_fact_revisions_edges_ad" to "AFTER DELETE ON fact_revisions BEGIN " +
            "DELETE FROM dependency_edges WHERE dependentKind = 'FACT_REVISION' AND dependentId = OLD.id; " +
            "DELETE FROM dependency_edges WHERE dependencyKind = 'FACT_REVISION' AND dependencyId = OLD.id; END",
        // fact_evidence (C2 §4.3, C2-R7, C2-R8, C2-R10, C2-R18)
        "c2_fact_evidence_insert" to "BEFORE INSERT ON fact_evidence BEGIN " +
            raise("C2 §4.3: role is VALUE or CURRENCY_CONTEXT", "NEW.role NOT IN ('VALUE', 'CURRENCY_CONTEXT')") +
            raise(
                "C2-R7: a USER revision has no evidence",
                "EXISTS (SELECT 1 FROM fact_revisions WHERE id = NEW.revisionId AND origin = 'USER')",
            ) +
            raise(
                "C2-R18: evidence belongs to the Source of its fact",
                "NOT EXISTS (SELECT 1 FROM fact_revisions r JOIN facts f ON f.id = r.factId " +
                    "WHERE r.id = NEW.revisionId AND f.sourceId = NEW.sourceId)",
            ) +
            raise(
                "C2-R18: evidence names a span of the same Source and reading",
                "NOT EXISTS (SELECT 1 FROM ocr_spans s JOIN ocr_revisions o ON o.id = s.revisionId " +
                    "WHERE s.id = NEW.ocrSpanId AND s.revisionId = NEW.ocrRevisionId AND o.sourceId = NEW.sourceId)",
            ) +
            raise(
                "C2 §4.3: the correction is of the evidence span",
                "NEW.ocrUserRevisionId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions " +
                    "WHERE id = NEW.ocrUserRevisionId AND spanId = NEW.ocrSpanId AND revisionId = NEW.ocrRevisionId)",
            ) +
            raise(
                "C2-R8: the region is the span line box",
                "NOT EXISTS (SELECT 1 FROM ocr_spans s WHERE s.id = NEW.ocrSpanId AND s.`left` IS NEW.regionLeft " +
                    "AND s.top IS NEW.regionTop AND s.`right` IS NEW.regionRight AND s.bottom IS NEW.regionBottom " +
                    "AND s.coordinateSystem IS NEW.regionCoordinateSystem)",
            ) +
            raise(
                "C2 §4.3: text offsets are both set, ordered and non-negative",
                "(NEW.textStart IS NULL) <> (NEW.textEnd IS NULL) OR NEW.textStart < 0 OR NEW.textEnd < NEW.textStart",
            ) +
            raise(
                "C2-R10: a revision has one VALUE row",
                "NEW.role = 'VALUE' AND EXISTS (SELECT 1 FROM fact_evidence " +
                    "WHERE revisionId = NEW.revisionId AND role = 'VALUE')",
            ) +
            raise(
                "C2-R10: CURRENCY_CONTEXT rows belong to MONEY revisions",
                "NEW.role = 'CURRENCY_CONTEXT' AND NOT EXISTS (SELECT 1 FROM fact_revisions " +
                    "WHERE id = NEW.revisionId AND kind = 'MONEY')",
            ) +
            raise(
                "C2-R10: at most two CURRENCY_CONTEXT rows",
                "NEW.role = 'CURRENCY_CONTEXT' AND (SELECT COUNT(*) FROM fact_evidence " +
                    "WHERE revisionId = NEW.revisionId AND role = 'CURRENCY_CONTEXT') >= 2",
            ) +
            raise(
                "C2-R10: all evidence of a revision is from one reading",
                "EXISTS (SELECT 1 FROM fact_evidence WHERE revisionId = NEW.revisionId " +
                    "AND ocrRevisionId <> NEW.ocrRevisionId)",
            ) +
            " END",
        "c2_fact_evidence_edge_ai" to "AFTER INSERT ON fact_evidence BEGIN " +
            "INSERT OR IGNORE INTO dependency_edges(edgeKind, dependentKind, dependentId, dependentSourceId, " +
            "dependencyKind, dependencyId, dependencySourceId) VALUES ('EVIDENCE', 'FACT_REVISION', " +
            "NEW.revisionId, NEW.sourceId, 'OCR_SPAN', NEW.ocrSpanId, NEW.sourceId); END",
        "c2_fact_evidence_edge_ad" to "AFTER DELETE ON fact_evidence BEGIN " +
            "DELETE FROM dependency_edges WHERE edgeKind = 'EVIDENCE' AND dependentKind = 'FACT_REVISION' " +
            "AND dependentId = OLD.revisionId AND dependencyKind = 'OCR_SPAN' AND dependencyId = OLD.ocrSpanId " +
            "AND NOT EXISTS (SELECT 1 FROM fact_evidence WHERE revisionId = OLD.revisionId " +
            "AND ocrSpanId = OLD.ocrSpanId); END",
        "c2_fact_evidence_immutable" to "BEFORE UPDATE ON fact_evidence BEGIN " +
            "SELECT RAISE(ABORT, 'C2 §4.3: evidence is immutable'); END",
        // review_events (C2 §4.5, C2-R12)
        "c2_review_events_insert" to "BEFORE INSERT ON review_events BEGIN " +
            raise(
                "C2 §4.5: decision is CONFIRM, REJECT or REOPEN",
                "NEW.decision NOT IN ('CONFIRM', 'REJECT', 'REOPEN')",
            ) +
            raise("C2 §4.5: sequence starts at 1", "NEW.sequence < 1") +
            " END",
        "c2_review_events_immutable" to "BEFORE UPDATE ON review_events BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R12: review events are append-only'); END",
        // fact_extraction_runs (C2 §4.6)
        "c2_fact_extraction_runs_insert" to "BEFORE INSERT ON fact_extraction_runs BEGIN " +
            raise("C2 §4.6: trigger is AUTO or USER", "NEW.`trigger` NOT IN ('AUTO', 'USER')") +
            raise("C2 §4.6: unknown outcome", "NEW.outcome NOT IN $OUTCOMES") +
            " END",
        // dependency_edges (C2 §4.8, C2-R18)
        "c2_dependency_edges_insert" to "BEFORE INSERT ON dependency_edges BEGIN " +
            raise("C2-R18: an edge never crosses Sources", "NEW.dependentSourceId IS NOT NEW.dependencySourceId") +
            raise("C2-R18: edge kinds are EVIDENCE and BASIS", "NEW.edgeKind NOT IN ('EVIDENCE', 'BASIS')") +
            raise(
                "C2-R18: an EVIDENCE edge mirrors an evidence row",
                "NEW.edgeKind = 'EVIDENCE' AND NOT EXISTS (SELECT 1 FROM fact_evidence " +
                    "WHERE revisionId = NEW.dependentId AND ocrSpanId = NEW.dependencyId " +
                    "AND sourceId = NEW.dependentSourceId AND NEW.dependentKind = 'FACT_REVISION' " +
                    "AND NEW.dependencyKind = 'OCR_SPAN')",
            ) +
            raise(
                "C2-R18: a BASIS edge points to an older non-USER revision of the same fact",
                "NEW.edgeKind = 'BASIS' AND NOT EXISTS (SELECT 1 FROM fact_revisions d " +
                    "JOIN fact_revisions p ON p.factId = d.factId JOIN facts f ON f.id = d.factId " +
                    "WHERE d.id = NEW.dependentId AND p.id = NEW.dependencyId AND p.revisionNo < d.revisionNo " +
                    "AND d.origin = 'USER' AND p.origin <> 'USER' AND f.sourceId = NEW.dependentSourceId " +
                    "AND NEW.dependentKind = 'FACT_REVISION' AND NEW.dependencyKind = 'FACT_REVISION')",
            ) +
            " END",
        "c2_dependency_edges_immutable" to "BEFORE UPDATE ON dependency_edges BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R18: edges are never updated'); END",
        // ocr_user_revisions (C2-R15, C2-R20)
        "c2_ocr_user_revisions_supersedes" to "BEFORE INSERT ON ocr_user_revisions BEGIN " +
            raise(
                "C2-R15: a correction names the current correction",
                "NEW.supersedesId IS NULL AND EXISTS (SELECT 1 FROM ocr_user_revisions " +
                    "WHERE revisionId = NEW.revisionId AND spanId IS NEW.spanId)",
            ) +
            raise(
                "C2-R15: a correction supersedes the head of the same reading and span",
                "NEW.supersedesId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions p " +
                    "WHERE p.id = NEW.supersedesId AND p.revisionId = NEW.revisionId AND p.spanId IS NEW.spanId " +
                    "AND NOT EXISTS (SELECT 1 FROM ocr_user_revisions n WHERE n.supersedesId = p.id))",
            ) +
            " END",
        "c2_ocr_user_revisions_supersedes_immutable" to "BEFORE UPDATE OF supersedesId ON ocr_user_revisions " +
            "WHEN NEW.supersedesId IS NOT OLD.supersedesId BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R15: supersession is immutable'); END",
        "c2_ocr_user_revisions_delete_guard" to "BEFORE DELETE ON ocr_user_revisions " +
            "WHEN EXISTS (SELECT 1 FROM ocr_user_revisions WHERE supersedesId = OLD.id) " +
            "AND NOT EXISTS (SELECT 1 FROM ocr_revisions o JOIN sources s ON s.id = o.sourceId " +
            "WHERE o.id = OLD.revisionId AND s.state = 'DELETING') BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R20: a superseded correction is deleted only with its Source'); END",
        // ocr_revisions (C2-R20)
        "c2_ocr_revisions_delete_guard" to "BEFORE DELETE ON ocr_revisions " +
            "WHEN EXISTS (SELECT 1 FROM sources WHERE id = OLD.sourceId AND currentOcrRevisionId = OLD.id " +
            "AND state <> 'DELETING') BEGIN " +
            "SELECT RAISE(ABORT, 'C2-R20: the current reading is deleted only with its Source'); END",
        // sources (C2-R14)
        "c2_sources_current_reading" to "BEFORE UPDATE OF currentOcrRevisionId ON sources " +
            "WHEN NEW.currentOcrRevisionId IS NOT OLD.currentOcrRevisionId BEGIN " +
            raise("C2-R14: the current reading never returns to NULL", "NEW.currentOcrRevisionId IS NULL") +
            raise(
                "C2-R14: the current reading is a READY reading of the Source",
                "NOT EXISTS (SELECT 1 FROM ocr_revisions WHERE id = NEW.currentOcrRevisionId " +
                    "AND sourceId = NEW.id AND state = 'READY')",
            ) +
            raise(
                "C2-R14: the current reading moves only to a later reading",
                "OLD.currentOcrRevisionId IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ocr_revisions n " +
                    "JOIN ocr_revisions c ON c.id = OLD.currentOcrRevisionId WHERE n.id = NEW.currentOcrRevisionId " +
                    "AND (n.startedAt > c.startedAt OR (n.startedAt = c.startedAt AND n.rowid > c.rowid)))",
            ) +
            " END",
    )

    val names: List<String> = triggers.map { it.first }

    /** DROP then CREATE for each trigger, in order. */
    fun statements(): List<String> = triggers.flatMap { (name, body) ->
        listOf("DROP TRIGGER IF EXISTS $name", "CREATE TRIGGER $name $body")
    }

    fun create(db: SupportSQLiteDatabase) = statements().forEach(db::execSQL)
}
