package org.openlife.app.settings

/** One displayed line of a bundled Markdown document: a heading or plain text. */
data class DocumentLine(val heading: Boolean, val text: String)

/**
 * P1-08-R2/R5: bundled Markdown (licence report, privacy policy) as plain
 * lines. Link syntax becomes plain text with the address visible; nothing is
 * a link, so nothing can be opened.
 */
object DocumentLines {
    fun parse(markdown: String): List<DocumentLine> {
        val lines = mutableListOf<DocumentLine>()
        val paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotBlank()) lines += DocumentLine(heading = false, text = paragraph.toString())
            paragraph.clear()
        }

        markdown.lineSequence().map(String::trim).forEach { raw ->
            val quoted = raw.removePrefix(">").trim()
            when {
                quoted.isEmpty() -> flush()

                raw.startsWith("#") -> {
                    flush()
                    inline(raw.trimStart('#')).takeIf(String::isNotEmpty)?.let { lines += DocumentLine(true, it) }
                }

                LIST_ITEM.containsMatchIn(quoted) -> {
                    flush()
                    lines += DocumentLine(heading = false, text = inline(quoted.replaceFirst(LIST_ITEM, "")))
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(inline(quoted))
                }
            }
        }
        flush()
        return lines
    }

    /** Link syntax becomes visible text and address; emphasis and code marks are dropped. */
    private fun inline(text: String): String = text
        .replace(LINK) { match ->
            val (label, target) = match.destructured
            // The document's own words, not app copy: the address stays visible after its label.
            if (label.isBlank() || label == target) target else buildString { append(label, " (", target, ")") }
        }
        .replace("**", "")
        .replace("`", "")
        .trim()

    private val LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")
    private val LIST_ITEM = Regex("""^[-*]\s+""")
}
