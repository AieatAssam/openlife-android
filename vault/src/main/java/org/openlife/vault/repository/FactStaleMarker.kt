package org.openlife.vault.repository

import org.openlife.vault.facts.DependencyNodeKind
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.StaleReason
import org.openlife.vault.storage.DependencyNode
import org.openlife.vault.storage.FactDao

/**
 * C2-R19 / C2-R40 / C2-R63: the one traversal over `dependency_edges` that
 * marks revisions STALE. Callers hold the mutation and run it inside the
 * transaction of the event that triggers it. The revisions that depend
 * directly on the changed span or reading get [StaleReason] as given; every
 * revision reached through them gets BASIS_STALE. Each node is visited once;
 * a chain deeper than [FactLimits.MAX_TRAVERSAL_DEPTH] throws, so the whole
 * transaction rolls back. The Source's fact-input epoch moves on every call,
 * whether or not anything was marked (ZZ-11).
 */
internal class FactStaleMarker(private val dao: FactDao, private val clock: () -> Long) {
    suspend fun markForSpan(spanId: String, reason: StaleReason): Int {
        val sourceId = dao.sourceOfSpan(spanId) ?: return 0
        return markFrom(sourceId, dao.dependentsOfSpan(spanId), reason)
    }

    suspend fun markForReading(ocrRevisionId: String, reason: StaleReason): Int {
        val sourceId = dao.sourceOfReading(ocrRevisionId) ?: return 0
        return markFrom(sourceId, dao.dependentsOfReading(ocrRevisionId), reason)
    }

    private suspend fun markFrom(sourceId: String, direct: List<DependencyNode>, reason: StaleReason): Int {
        require(reason != StaleReason.UNRECOGNISED && reason != StaleReason.BASIS_STALE)
        val staleAt = clock()
        val visited = mutableSetOf<String>()
        var frontier = revisions(direct).filter(visited::add)
        var stepReason = reason
        var depth = 0
        var marked = 0
        while (frontier.isNotEmpty()) {
            check(depth < FactLimits.MAX_TRAVERSAL_DEPTH) { "dependency chain exceeds the traversal depth" }
            frontier.chunked(CHUNK).forEach { marked += dao.markStale(it, stepReason.name, staleAt) }
            val next = frontier.chunked(CHUNK).flatMap { dao.dependentsOf(DependencyNodeKind.FACT_REVISION.name, it) }
            frontier = revisions(next).filter(visited::add)
            stepReason = StaleReason.BASIS_STALE
            depth++
        }
        dao.bumpFactInputEpoch(sourceId)
        return marked
    }

    private fun revisions(nodes: List<DependencyNode>): List<String> =
        nodes.filter { it.kind == DependencyNodeKind.FACT_REVISION.name }.map { it.id }.distinct()

    private companion object {
        /** Well under SQLite's bound-parameter limit. */
        const val CHUNK = 500
    }
}
