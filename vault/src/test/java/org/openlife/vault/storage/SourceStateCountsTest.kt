package org.openlife.vault.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.model.SourceState
import org.openlife.vault.storage.HostSqlite.Companion.createFromExportedSchema
import java.io.File

/**
 * P1-08-R4 (review K-03): About counts sources by state with one GROUP BY on
 * the state column, never loading rows (wrapped keys, digests). The query
 * runs on host SQLite against the exported v5 schema; decoding is tolerant.
 */
class SourceStateCountsTest {
    private val host = HostSqlite()

    @After
    fun tearDown() = host.close()

    @Test
    fun unknownStatesShareTheUnrecognisedBucket() {
        val rows = listOf(StateCount("READY", 3), StateCount("FUTURE_STATE", 2), StateCount("ready", 1))
        assertEquals(mapOf(SourceState.READY to 3, SourceState.UNRECOGNISED to 3), stateCounts(rows))
        assertEquals(emptyMap<SourceState, Int>(), stateCounts(emptyList()))
    }

    @Test
    fun groupByQueryCountsEveryStoredStateOnHostSqlite() {
        host.createFromExportedSchema(File("schemas/org.openlife.vault.storage.OpenLifeDatabase/5.json"))
        listOf("READY", "READY", "STAGED", "CORRUPT", "FROM_A_NEWER_BUILD").forEachIndexed { index, state ->
            host.exec(
                "INSERT INTO sources(id, state, importedAt, intakeKind, wrappedDek, sha256) " +
                    "VALUES (?, ?, ?, 'SHARE', X'02', X'01')",
                "00000000-0000-0000-0000-00000000000$index",
                state,
                index.toLong(),
            )
        }
        val rows = host.query(COUNT_SOURCES_BY_STATE).map { (state, n) ->
            StateCount(state as String, (n as Long).toInt())
        }
        val expected = mapOf(
            SourceState.READY to 2,
            SourceState.STAGED to 1,
            SourceState.CORRUPT to 1,
            SourceState.UNRECOGNISED to 1,
        )
        assertEquals(expected, stateCounts(rows))
        val selected = COUNT_SOURCES_BY_STATE.substringAfter("SELECT ").substringBefore(" FROM")
        assertEquals("the query reads the state column only", "state, COUNT(*) AS n", selected)
    }
}
