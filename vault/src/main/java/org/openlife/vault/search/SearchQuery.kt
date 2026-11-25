package org.openlife.vault.search

import java.util.Locale

/**
 * P2-08-R3: user input never reaches MATCH or LIKE as syntax. The text is cut
 * into word tokens (letters and digits of any script); each token becomes a
 * quoted FTS5 string, the last one a prefix (`"tok"*`), joined by implicit
 * AND. FTS5 operators typed by the user (`OR`, `NOT`, `NEAR(`, `*`, `^`,
 * `col:`) are therefore plain words or dropped punctuation. The expression is
 * bound as an argument, never concatenated into SQL.
 */
object SearchQuery {
    const val MAX_TOKENS = 8
    const val MAX_TOKEN_LENGTH = 64
    const val MAX_INPUT_LENGTH = 200

    fun tokens(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var codePoints = 0
        fun flush() {
            if (current.isNotEmpty() && tokens.size < MAX_TOKENS) tokens += current.toString()
            current.clear()
            codePoints = 0
        }
        raw.take(MAX_INPUT_LENGTH).codePoints().forEach { codePoint ->
            if (Character.isLetterOrDigit(codePoint)) {
                if (codePoints < MAX_TOKEN_LENGTH) current.appendCodePoint(codePoint)
                codePoints++
            } else {
                flush()
            }
        }
        flush()
        return tokens
    }

    /** The FTS5 MATCH argument, or null when there is nothing to search. */
    fun matchExpression(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        return tokens.mapIndexed { index, token ->
            val quoted = "\"" + token.replace("\"", "\"\"") + "\""
            if (index == tokens.lastIndex) "$quoted*" else quoted
        }.joinToString(" ")
    }

    /** One `%token%` pattern per token for `LIKE ? ESCAPE '\'`, with `%`, `_` and `\` literal. */
    fun likePatterns(tokens: List<String>): List<String> = tokens.map { token ->
        val escaped = token.lowercase(Locale.ROOT)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        "%$escaped%"
    }
}
