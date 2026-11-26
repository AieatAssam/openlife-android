package org.openlife.vault.facts.extract

/**
 * Canonical JSON (ADR-0008 §2): fixed key order, no insignificant
 * whitespace, no number type, exact escaping, so equal values are equal
 * bytes (C2-R5). Serialises from the typed value only.
 */
internal object CanonicalJson {
    private const val CONTROL_LIMIT = 0x20
    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 2

    fun of(single: FactValue?, candidates: List<FactValue>): String = buildString {
        if (single != null) {
            value(this, single)
        } else {
            append("{\"candidates\":[")
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) append(',')
                value(this, candidate)
            }
            append("]}")
        }
    }

    /** True when [text] holds a surrogate that is not part of a pair; such a value is never written. */
    fun hasUnpairedSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val pairs = char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
            if (pairs) {
                index += 2
            } else {
                if (char.isSurrogate()) return true
                index++
            }
        }
        return false
    }

    /** Every string a value would write, for the surrogate check. */
    fun strings(value: FactValue): List<String?> = when (value) {
        is DateValue -> listOf(value.canonicalText())
        is TimeValue -> listOf(value.canonicalText())
        is DateTimeValue -> listOf(value.canonicalText())
        is MoneyValue -> listOf(value.amount, value.currency, value.rawSymbol)
        is TextValue -> listOf(value.normalized, value.raw)
    }

    private fun value(out: StringBuilder, value: FactValue) {
        when (value) {
            is DateValue -> fields(out, "date" to value.canonicalText())
            is TimeValue -> fields(out, "time" to value.canonicalText())
            is DateTimeValue -> fields(out, "date" to value.date.canonicalText(), "time" to value.time.canonicalText())
            is MoneyValue -> money(out, value)
            is TextValue -> fields(out, "normalized" to value.normalized, "raw" to value.raw)
        }
    }

    private fun money(out: StringBuilder, value: MoneyValue) =
        fields(out, "amount" to value.amount, "currency" to value.currency, "rawSymbol" to value.rawSymbol)

    private fun fields(out: StringBuilder, vararg pairs: Pair<String, String?>) {
        out.append('{')
        pairs.forEachIndexed { index, (name, text) ->
            if (index > 0) out.append(',')
            string(out, name)
            out.append(':')
            if (text == null) out.append("null") else string(out, text)
        }
        out.append('}')
    }

    private fun string(out: StringBuilder, text: String) {
        out.append('"')
        for (char in text) {
            val escape = shortEscape(char)
            when {
                escape != null -> out.append(escape)
                char.code < CONTROL_LIMIT -> out.append("\\u00").append(hex(char))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private fun hex(char: Char): String = char.code.toString(HEX_RADIX).padStart(HEX_WIDTH, '0')

    private fun shortEscape(char: Char): String? = when (char) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\b' -> "\\b"
        '\t' -> "\\t"
        '\n' -> "\\n"
        '\u000C' -> "\\f"
        '\r' -> "\\r"
        else -> null
    }
}
