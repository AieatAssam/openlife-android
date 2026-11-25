package org.openlife.vault.search

/** P2-08-R3/R5: the two query shapes (FTS5 and LIKE fallback) with their bound arguments. */
object SearchSql {
    const val MAX_LIMIT = 200

    data class Statement(val sql: String, val args: List<Any>)

    fun fts(matchExpression: String, limit: Int): Statement = TODO("P2-08 RED")

    fun like(patterns: List<String>, limit: Int): Statement = TODO("P2-08 RED")
}
