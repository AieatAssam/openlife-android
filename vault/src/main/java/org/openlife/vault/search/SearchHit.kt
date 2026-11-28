package org.openlife.vault.search

import java.util.UUID

/**
 * P2-08-R3: one local search result. It always names the source and the
 * revision it came from; [spanId] is null only for a whole-revision
 * correction. [snippet] is plain text (inert, never a link) and
 * [highlights] are the matched ranges inside it.
 */
data class SearchHit(
    val sourceId: UUID,
    val revisionId: UUID,
    val spanId: UUID?,
    val snippet: String,
    val isCorrection: Boolean,
    val highlights: List<IntRange> = emptyList(),
    /** P3-07-R1: set on a fact hit (its revision and labels); null for OCR text. */
    val fact: SearchFact? = null,
)

/**
 * P3-07-R1/R2: a fact hit. [revisionId] is the matched fact revision (the
 * hit carries it so the UI can open that fact); [kind] and [status] are the
 * stored enum names, [origin] is EXTRACTED or USER.
 */
data class SearchFact(
    val revisionId: UUID,
    val kind: String,
    val status: String,
    val origin: String,
)
