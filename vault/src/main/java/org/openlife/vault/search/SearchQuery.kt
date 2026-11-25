package org.openlife.vault.search

/**
 * P2-08-R3: user input never reaches MATCH or LIKE as syntax. The text is cut
 * into word tokens; each token becomes a quoted FTS5 string (the last one a
 * prefix), joined by implicit AND. Bound as an argument, never concatenated.
 */
object SearchQuery {
    const val MAX_TOKENS = 8
    const val MAX_TOKEN_LENGTH = 64
    const val MAX_INPUT_LENGTH = 200

    fun tokens(raw: String): List<String> = TODO("P2-08 RED")

    fun matchExpression(tokens: List<String>): String? = TODO("P2-08 RED")

    fun likePatterns(tokens: List<String>): List<String> = TODO("P2-08 RED")
}
