package org.openlife.vault.storage

import androidx.room.Dao
import androidx.room.Query

/**
 * C2-R43 / C2-R61 (finding F-70): the one routine that removes a Source's
 * rows, used by DeletionRepository and by RecoveryRepository's DELETING
 * branch. Callers run it in one transaction, after the artefact files are
 * gone, on a Source that is already DELETING (C1-R7); the pointer-guard
 * triggers refuse it on any other state (C2-R20).
 *
 * The shared cascade contract. Every statement works on the whole set of the
 * Source, so no statement deletes a row that a surviving row of that Source
 * still references, and the result is the same with `PRAGMA foreign_keys`
 * on or off (cascade is only the second line). Dependent tables per Source,
 * in the order they are emptied:
 * 1. `review_events` of the Source's fact revisions;
 * 2. `fact_evidence` (its triggers delete the EVIDENCE edges);
 * 3. `fact_revisions`, in one statement (its triggers delete the BASIS edges);
 * 4. `facts`;
 * 5. `fact_extraction_runs`;
 * 6. `ocr_span_reviews`, `ocr_user_revisions`, `ocr_spans`, `ocr_revisions`
 *    (their triggers delete the FTS rows);
 * 7. the `sources` row, through [SourceDao.deleteById].
 *
 * Afterwards no row of any fact or OCR table, and no `dependency_edges` row
 * on either side, names the Source. It is the only code that removes a
 * Source row other than a STAGED cleanup (SourceRowRemovalBoundaryTest).
 */
object SourceRowRemoval {
    const val SOURCE_STATE = "SELECT state FROM sources WHERE id = :sourceId"

    const val DELETE_REVIEW_EVENTS = """
        DELETE FROM review_events WHERE revisionId IN
            (SELECT r.id FROM fact_revisions r JOIN facts f ON f.id = r.factId WHERE f.sourceId = :sourceId)
        """

    const val DELETE_FACT_EVIDENCE = "DELETE FROM fact_evidence WHERE sourceId = :sourceId"

    const val DELETE_FACT_REVISIONS =
        "DELETE FROM fact_revisions WHERE factId IN (SELECT id FROM facts WHERE sourceId = :sourceId)"

    const val DELETE_FACTS = "DELETE FROM facts WHERE sourceId = :sourceId"

    const val DELETE_EXTRACTION_RUNS = "DELETE FROM fact_extraction_runs WHERE sourceId = :sourceId"

    const val DELETE_SPAN_REVIEWS = """
        DELETE FROM ocr_span_reviews WHERE spanId IN
            (SELECT s.id FROM ocr_spans s JOIN ocr_revisions r ON r.id = s.revisionId WHERE r.sourceId = :sourceId)
        """

    const val DELETE_CORRECTIONS =
        "DELETE FROM ocr_user_revisions WHERE revisionId IN (SELECT id FROM ocr_revisions WHERE sourceId = :sourceId)"

    const val DELETE_SPANS =
        "DELETE FROM ocr_spans WHERE revisionId IN (SELECT id FROM ocr_revisions WHERE sourceId = :sourceId)"

    const val DELETE_READINGS = "DELETE FROM ocr_revisions WHERE sourceId = :sourceId"

    /** Step 7, run through [SourceDao.deleteById]. */
    const val DELETE_SOURCE = "DELETE FROM sources WHERE id = :id"

    /**
     * Runs steps 1-7; [deleteSource] is step 7 ([SourceDao.deleteById]).
     * Throws, leaving the caller's transaction to roll back, unless the
     * Source is DELETING.
     */
    suspend fun remove(rows: SourceRowRemovalDao, sourceId: String, deleteSource: suspend (String) -> Unit) {
        check(rows.sourceState(sourceId) == DELETING) { "only a DELETING Source loses its rows" }
        rows.deleteReviewEvents(sourceId)
        rows.deleteFactEvidence(sourceId)
        rows.deleteFactRevisions(sourceId)
        rows.deleteFacts(sourceId)
        rows.deleteExtractionRuns(sourceId)
        rows.deleteSpanReviews(sourceId)
        rows.deleteCorrections(sourceId)
        rows.deleteSpans(sourceId)
        rows.deleteReadings(sourceId)
        deleteSource(sourceId)
    }

    private const val DELETING = "DELETING"
}

/** The statements of [SourceRowRemoval], in the order it runs them. */
@Dao
interface SourceRowRemovalDao {
    @Query(SourceRowRemoval.SOURCE_STATE)
    suspend fun sourceState(sourceId: String): String?

    @Query(SourceRowRemoval.DELETE_REVIEW_EVENTS)
    suspend fun deleteReviewEvents(sourceId: String)

    @Query(SourceRowRemoval.DELETE_FACT_EVIDENCE)
    suspend fun deleteFactEvidence(sourceId: String)

    @Query(SourceRowRemoval.DELETE_FACT_REVISIONS)
    suspend fun deleteFactRevisions(sourceId: String)

    @Query(SourceRowRemoval.DELETE_FACTS)
    suspend fun deleteFacts(sourceId: String)

    @Query(SourceRowRemoval.DELETE_EXTRACTION_RUNS)
    suspend fun deleteExtractionRuns(sourceId: String)

    @Query(SourceRowRemoval.DELETE_SPAN_REVIEWS)
    suspend fun deleteSpanReviews(sourceId: String)

    @Query(SourceRowRemoval.DELETE_CORRECTIONS)
    suspend fun deleteCorrections(sourceId: String)

    @Query(SourceRowRemoval.DELETE_SPANS)
    suspend fun deleteSpans(sourceId: String)

    @Query(SourceRowRemoval.DELETE_READINGS)
    suspend fun deleteReadings(sourceId: String)
}
