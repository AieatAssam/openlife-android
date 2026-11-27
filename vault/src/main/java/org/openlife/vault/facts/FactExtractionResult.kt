package org.openlife.vault.facts

import org.openlife.vault.facts.extract.ExtractedKind

/** How one extraction run ended (C2 §7.6). */
sealed interface FactExtractionResult {
    /** The run ended with [outcome]; the outcome's row (C2 §7.6) is written unless it writes none. */
    data class Finished(
        val outcome: ExtractionOutcome,
        val candidateCount: Int,
        val createdRevisionCount: Int,
        val skippedKinds: Set<ExtractedKind>,
    ) : FactExtractionResult

    /** C2-R31: the epoch, reading or Source state changed during the run, so nothing was written. */
    object Superseded : FactExtractionResult
}
