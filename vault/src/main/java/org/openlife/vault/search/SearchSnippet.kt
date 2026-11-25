package org.openlife.vault.search

/** P2-08-R4: a bounded excerpt of a hit's text with the matched ranges, built in Kotlin for both modes. */
object SearchSnippet {
    const val MAX_LENGTH = 160

    data class Snippet(val text: String, val highlights: List<IntRange>)

    fun of(text: String, tokens: List<String>): Snippet = TODO("P2-08 RED")
}
