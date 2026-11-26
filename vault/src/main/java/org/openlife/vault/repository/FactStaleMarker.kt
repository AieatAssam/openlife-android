package org.openlife.vault.repository

import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.FactDao

/**
 * C2-R19 / C2-R40: the one traversal over `dependency_edges` that marks
 * revisions STALE. Callers hold the mutation and run it inside the
 * transaction of the event that triggers it.
 */
internal class FactStaleMarker(private val dao: FactDao, private val clock: () -> Long) {
    suspend fun markForSpan(spanId: String, reason: StaleReason): Int = TODO("P3-02 GREEN")

    suspend fun markForReading(ocrRevisionId: String, reason: StaleReason): Int = TODO("P3-02 GREEN")
}
