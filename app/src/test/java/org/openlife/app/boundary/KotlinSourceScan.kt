package org.openlife.app.boundary

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * P1-10: a small structural reader for Kotlin source, enough for the
 * accessibility boundary tests to see whole calls rather than single lines.
 * Comments and string contents are masked (offsets kept), so brackets inside
 * them do not count; the original text is kept for comments and literals.
 * It is a tripwire, not a parser: it does not resolve names or types.
 */
internal class KotlinSource(val path: Path, val text: String) {
    /** [text] with comment and string-literal characters replaced by spaces (newlines kept). */
    val code: String = mask(text)

    fun lineOf(offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** Calls of [name] (a plain call, `name(...)`, or a modifier, `.name(...)` when [name] starts with '.'). */
    fun calls(name: String): List<SourceCall> {
        val pattern = if (name.startsWith(".")) {
            Regex("""\.${Regex.escape(name.removePrefix("."))}\s*[({]""")
        } else {
            Regex("""(?<![\w.])${Regex.escape(name)}\s*[({]""")
        }
        return pattern.findAll(code).mapNotNull { callStartingAt(it.range.first, name.removePrefix(".")) }.toList()
    }

    /** The innermost call whose argument list contains [offset], or null at top level. */
    fun enclosingCall(offset: Int): SourceCall? {
        var depth = 0
        var index = offset - 1
        while (index >= 0) {
            when (code[index]) {
                ')' -> depth++
                '(' -> if (depth == 0) return callForOpenParen(index) else depth--
            }
            index--
        }
        return null
    }

    /** The body of `fun [name](...) ... { body }` in this file, or null. */
    fun functionBody(name: String): String? {
        val match = Regex("""\bfun\s+(?:[\w.<>]+\.)?${Regex.escape(name)}\s*\(""").find(code) ?: return null
        val open = code.indexOf('(', match.range.first)
        val close = matching(open, '(', ')') ?: return null
        val brace = code.indexOf('{', close)
        val equals = code.indexOf('=', close)
        if (brace < 0 || (equals in close..brace)) return null
        val end = matching(brace, '{', '}') ?: return null
        return text.substring(brace, end + 1)
    }

    private fun callStartingAt(start: Int, name: String): SourceCall? {
        var index = start
        while (index < code.length && code[index] != '(' && code[index] != '{') index++
        if (index >= code.length) return null
        return if (code[index] == '(') {
            callForOpenParen(index, start, name)
        } else {
            val end = matching(index, '{', '}') ?: return null
            SourceCall(this, name, start, "", text.substring(index, end + 1))
        }
    }

    private fun callForOpenParen(open: Int, knownStart: Int? = null, knownName: String? = null): SourceCall? {
        val close = matching(open, '(', ')') ?: return null
        var nameEnd = open
        while (nameEnd > 0 && code[nameEnd - 1].isWhitespace()) nameEnd--
        var nameStart = nameEnd
        while (nameStart > 0 && (code[nameStart - 1].isLetterOrDigit() || code[nameStart - 1] == '_')) nameStart--
        val name = knownName ?: code.substring(nameStart, nameEnd)
        var after = close + 1
        while (after < code.length && code[after].isWhitespace()) after++
        val body = if (after < code.length && code[after] == '{') {
            matching(after, '{', '}')?.let { text.substring(after, it + 1) }
        } else {
            null
        }
        return SourceCall(this, name, knownStart ?: nameStart, text.substring(open + 1, close), body)
    }

    private fun matching(open: Int, opening: Char, closing: Char): Int? {
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                opening -> depth++
                closing -> if (--depth == 0) return index
            }
        }
        return null
    }

    companion object {
        fun load(path: Path): KotlinSource = KotlinSource(path, Files.readString(path))

        /** Every shipped Kotlin file under app/src/main/java. */
        fun app(projectDir: Path): List<KotlinSource> = Files.walk(projectDir.resolve("app/src/main/java")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .sorted()
                .map(::load)
                .collect(Collectors.toList())
        }

        @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        private fun mask(text: String): String {
            val out = StringBuilder(text)
            var index = 0
            fun blank(from: Int, to: Int) {
                for (position in from until to.coerceAtMost(text.length)) if (out[position] != '\n') out[position] = ' '
            }
            while (index < text.length) {
                when {
                    text.startsWith("//", index) -> {
                        val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                        blank(index, end)
                        index = end
                    }
                    text.startsWith("/*", index) -> {
                        val end = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }
                        blank(index, end)
                        index = end
                    }
                    text.startsWith("\"\"\"", index) -> {
                        val end = text.indexOf("\"\"\"", index + 3).let { if (it < 0) text.length else it + 3 }
                        blank(index + 3, end - 3)
                        index = end
                    }
                    text[index] == '"' -> {
                        var end = index + 1
                        while (end < text.length && text[end] != '"' && text[end] != '\n') end += if (text[end] == '\\') 2 else 1
                        blank(index + 1, end)
                        index = end + 1
                    }
                    text[index] == '\'' && index + 2 < text.length -> {
                        val end = if (text[index + 1] == '\\') text.indexOf('\'', index + 2) else index + 2
                        if (end > index && end < text.length && text[end] == '\'') blank(index + 1, end)
                        index = if (end > index) end + 1 else index + 1
                    }
                    else -> index++
                }
            }
            return out.toString()
        }
    }
}

/** One call: its [name], the original text of its [args] and of its trailing lambda [body], if any. */
internal class SourceCall(val source: KotlinSource, val name: String, val start: Int, val args: String, val body: String?) {
    val all: String get() = args + body.orEmpty()

    fun location(): String = "${source.path.fileName}:${source.lineOf(start)}"

    /** The [lines] source lines before the call, for justification comments. */
    fun preceding(lines: Int): String {
        val text = source.text
        var index = start
        repeat(lines + 1) { index = text.lastIndexOf('\n', index - 1).coerceAtLeast(0) }
        return text.substring(index, start)
    }
}
