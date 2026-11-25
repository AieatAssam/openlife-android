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
)
