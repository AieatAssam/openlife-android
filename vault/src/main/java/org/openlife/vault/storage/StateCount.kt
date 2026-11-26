package org.openlife.vault.storage

import org.openlife.vault.model.SourceState

/** P1-08-R4 (review K-03): the state column and a count, so counting reads no row content. */
data class StateCount(val state: String, val n: Int)

/** Counts by stored state text; no other column is read. */
internal const val COUNT_SOURCES_BY_STATE = "SELECT state, COUNT(*) AS n FROM sources GROUP BY state"

/**
 * Decodes [rows] with the tolerant state decoding: every text that is not a
 * known state adds to [SourceState.UNRECOGNISED]. States with no rows are absent.
 */
internal fun stateCounts(rows: List<StateCount>): Map<SourceState, Int> = rows
    .groupingBy { decodeStored(it.state, SourceState.UNRECOGNISED) }
    .fold(0) { total, row -> total + row.n }
