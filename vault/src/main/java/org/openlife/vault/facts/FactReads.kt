package org.openlife.vault.facts

import org.openlife.vault.storage.FactEvidenceEntity

/** One VALUE or CURRENCY_CONTEXT span a fact revision was read from (C2-R34). */
data class FactEvidence(
    val role: EvidenceRole,
    val ocrRevisionId: String,
    val ocrSpanId: String,
    val textStart: Int?,
    val textEnd: Int?,
)

/** What the facts panel can say about the current reading's extraction (C2-R39). */
sealed interface ExtractionCheck {
    /** No extraction run has been recorded against the current reading. */
    object NotChecked : ExtractionCheck

    /** The newest run against the current reading completed; zero facts means "No facts found". */
    object Completed : ExtractionCheck

    /** The newest run against the current reading did not complete. */
    data class Failed(val outcome: ExtractionOutcome) : ExtractionCheck
}

/** Maps a stored evidence row to the domain type; an unknown role throws rather than being guessed. */
fun FactEvidenceEntity.toFactEvidence(): FactEvidence = FactEvidence(
    role = EvidenceRole.valueOf(role),
    ocrRevisionId = ocrRevisionId,
    ocrSpanId = ocrSpanId,
    textStart = textStart,
    textEnd = textEnd,
)

/** Maps the stored outcome of the current reading's newest run; null means no run exists. */
fun String?.toExtractionCheck(): ExtractionCheck = when (this) {
    null -> ExtractionCheck.NotChecked
    ExtractionOutcome.COMPLETED.name -> ExtractionCheck.Completed
    else -> ExtractionCheck.Failed(ExtractionOutcome.valueOf(this))
}

/** Maps the stored latest review decision; null means the revision was never reviewed. */
fun String?.toReviewDecision(): ReviewDecision? = this?.let { ReviewDecision.valueOf(it) }
