package org.openlife.vault.search

/**
 * P2-08-R3: user input never reaches MATCH or LIKE as syntax. The text is cut
 * into word tokens; each token becomes a quoted FTS5 string, the last one a
 * prefix (`"tok"*`), joined by implicit AND. FTS5 operators typed by the user
 * (`OR`, `NOT`, `NEAR(`, `*`, `^`, `col:`) are therefore plain words or
 * dropped punctuation. The expression is bound as an argument, never
 * concatenated into SQL.
 *
 * Token characters match the index tokenizer (unicode61): letters, numbers of
 * every kind (so `Ⅻ`, `x²` and `½` stay whole) and private-use characters.
 *
 * Review W-02: unicode61 cannot find words inside text written without
 * spaces (Chinese, Japanese, Thai, Lao, Khmer, Myanmar, Tibetan), and it
 * splits Indic words at their vowel signs. Input in those scripts is searched
 * as substrings instead ([needsSubstringSearch], [substringTerms]).
 */
object SearchQuery {
    const val MAX_TOKENS = 8
    const val MAX_TOKEN_LENGTH = 64
    const val MAX_INPUT_LENGTH = 200

    fun tokens(raw: String): List<String> = split(raw, keepMarks = false)

    /**
     * The terms for a substring (LIKE) search: like [tokens], but combining
     * marks stay inside a term, so a Thai or Devanagari word is kept whole.
     * A term that is only marks is dropped.
     */
    fun substringTerms(raw: String): List<String> = split(raw, keepMarks = true)

    /** True when [raw] has text that the word index cannot match as typed (review W-02). */
    fun needsSubstringSearch(raw: String): Boolean = raw.take(MAX_INPUT_LENGTH).codePoints().anyMatch { codePoint ->
        val script = Character.UnicodeScript.of(codePoint)
        script in UNSPACED_SCRIPTS || (isMark(codePoint) && script !in WORD_MARK_SCRIPTS)
    }

    /** The FTS5 MATCH argument, or null when there is nothing to search. */
    fun matchExpression(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        return tokens.mapIndexed { index, token ->
            val quoted = "\"" + token.replace("\"", "\"\"") + "\""
            if (index == tokens.lastIndex) "$quoted*" else quoted
        }.joinToString(" ")
    }

    /**
     * One `%term%` pattern per term for `lower(column) LIKE ? ESCAPE '\'`,
     * with `%`, `_` and `\` literal. Review W-05: the pattern is lowercased
     * exactly as SQLite's `lower()` lowercases the column (ASCII A-Z only),
     * so text typed exactly as stored is always found (`Ärzte` finds `Ärzte`).
     */
    fun likePatterns(terms: List<String>): List<String> = terms.map { term ->
        val escaped = asciiLowercase(term)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        "%$escaped%"
    }

    /** SQLite's built-in `lower()`: only A-Z change. */
    fun asciiLowercase(value: String): String = buildString(value.length) {
        value.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) }
    }

    private fun split(raw: String, keepMarks: Boolean): List<String> {
        val terms = mutableListOf<String>()
        val current = StringBuilder()
        var codePoints = 0
        var hasTokenCharacter = false
        fun flush() {
            if (hasTokenCharacter && terms.size < MAX_TOKENS) terms += current.toString()
            current.clear()
            codePoints = 0
            hasTokenCharacter = false
        }
        raw.take(MAX_INPUT_LENGTH).codePoints().forEach { codePoint ->
            val token = isTokenCharacter(codePoint)
            if (token || (keepMarks && isMark(codePoint))) {
                if (codePoints < MAX_TOKEN_LENGTH) current.appendCodePoint(codePoint)
                codePoints++
                hasTokenCharacter = hasTokenCharacter || token
            } else {
                flush()
            }
        }
        flush()
        return terms
    }

    private fun isTokenCharacter(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || Character.getType(codePoint).toByte() in TOKEN_TYPES

    private fun isMark(codePoint: Int): Boolean = Character.getType(codePoint).toByte() in MARK_TYPES

    private val TOKEN_TYPES = setOf(Character.LETTER_NUMBER, Character.OTHER_NUMBER, Character.PRIVATE_USE)
    private val MARK_TYPES = setOf(
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
    )
    private val UNSPACED_SCRIPTS = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.TIBETAN,
    )

    /** Scripts whose marks the index folds away (remove_diacritics), so word search still works. */
    private val WORD_MARK_SCRIPTS = setOf(
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.COMMON,
        Character.UnicodeScript.INHERITED,
    )
}
