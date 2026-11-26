package org.openlife.app.settings

/** One displayed line of a bundled Markdown document: a heading or plain text. */
data class DocumentLine(val heading: Boolean, val text: String)

/**
 * P1-08-R2/R5: bundled Markdown (licence report, privacy policy) as plain
 * lines. Link syntax becomes plain text with the address visible; nothing is
 * a link, so nothing can be opened.
 */
object DocumentLines {
    fun parse(markdown: String): List<DocumentLine> = TODO("P1-08 RED")
}
