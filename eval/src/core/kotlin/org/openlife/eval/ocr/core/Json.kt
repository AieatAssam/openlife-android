package org.openlife.eval.ocr.core

/**
 * A small strict JSON codec for the evaluation set (P2-04). It is shared by
 * the host generator and gate and by the on-device harness, so it uses only
 * the Kotlin standard library: no new dependency enters either build.
 *
 * Strict means: no trailing data, no duplicate keys, no NaN or infinity, no
 * comments, and a depth limit. Malformed input is a [JsonFormatException].
 */
sealed interface JsonValue

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    fun has(key: String): Boolean = key in fields

    fun value(key: String): JsonValue = fields[key] ?: throw JsonFormatException("missing field \"$key\"")

    fun string(key: String): String = (value(key) as? JsonString)?.value ?: typeError(key, "a string")

    fun bool(key: String): Boolean = (value(key) as? JsonBool)?.value ?: typeError(key, "a boolean")

    fun double(key: String): Double = (value(key) as? JsonNumber)?.toDouble() ?: typeError(key, "a number")

    fun long(key: String): Long = (value(key) as? JsonNumber)?.toLong() ?: typeError(key, "an integer")

    fun int(key: String): Int {
        val number = long(key)
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) typeError(key, "a 32-bit integer")
        return number.toInt()
    }

    fun obj(key: String): JsonObject = value(key) as? JsonObject ?: typeError(key, "an object")

    fun array(key: String): List<JsonValue> = (value(key) as? JsonArray)?.items ?: typeError(key, "an array")

    /** Null when the field is JSON `null`; a missing field is still an error. */
    fun optDouble(key: String): Double? = if (value(key) == JsonNull) null else double(key)

    fun optLong(key: String): Long? = if (value(key) == JsonNull) null else long(key)

    fun optInt(key: String): Int? = if (value(key) == JsonNull) null else int(key)

    fun optBool(key: String): Boolean? = if (value(key) == JsonNull) null else bool(key)

    fun optObj(key: String): JsonObject? = if (value(key) == JsonNull) null else obj(key)

    private fun typeError(key: String, expected: String): Nothing =
        throw JsonFormatException("field \"$key\" is not $expected")
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** Keeps the literal text so integers and decimals round-trip exactly. */
data class JsonNumber(val text: String) : JsonValue {
    fun toDouble(): Double = text.toDouble()

    fun toLong(): Long = text.toLongOrNull() ?: throw JsonFormatException("$text is not an integer")

    companion object {
        fun of(value: Long): JsonNumber = JsonNumber(value.toString())

        fun of(value: Int): JsonNumber = JsonNumber(value.toString())

        fun of(value: Double): JsonNumber {
            require(value.isFinite()) { "JSON numbers must be finite" }
            return JsonNumber(value.toString())
        }
    }
}

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

class JsonFormatException(message: String) : IllegalArgumentException(message)

fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonObject {
    val map = LinkedHashMap<String, JsonValue>()
    for ((key, value) in fields) {
        require(map.put(key, value) == null) { "duplicate key $key" }
    }
    return JsonObject(map)
}

fun jsonArray(items: List<JsonValue>): JsonArray = JsonArray(items)

fun String.json(): JsonString = JsonString(this)

fun Boolean.json(): JsonBool = JsonBool(this)

fun Int.json(): JsonNumber = JsonNumber.of(this)

fun Long.json(): JsonNumber = JsonNumber.of(this)

fun Double.json(): JsonNumber = JsonNumber.of(this)

fun Double?.jsonOrNull(): JsonValue = this?.json() ?: JsonNull

fun Long?.jsonOrNull(): JsonValue = this?.json() ?: JsonNull

fun Int?.jsonOrNull(): JsonValue = this?.json() ?: JsonNull

fun Boolean?.jsonOrNull(): JsonValue = this?.json() ?: JsonNull

object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonFormatException("trailing data at offset ${parser.position}")
        return value
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonFormatException("top-level value is not an object")

    /**
     * Deterministic pretty printing: two-space indent, fields in insertion
     * order, arrays of primitives on one line, a trailing newline.
     */
    fun write(value: JsonValue): String = buildString {
        writeValue(this, value, 0)
        append('\n')
    }

    private fun writeValue(out: StringBuilder, value: JsonValue, indent: Int) {
        when (value) {
            is JsonObject -> writeObject(out, value, indent)
            is JsonArray -> writeArray(out, value, indent)
            is JsonString -> writeString(out, value.value)
            is JsonNumber -> out.append(value.text)
            is JsonBool -> out.append(value.value)
            JsonNull -> out.append("null")
        }
    }

    private fun writeObject(out: StringBuilder, value: JsonObject, indent: Int) {
        if (value.fields.isEmpty()) {
            out.append("{}")
            return
        }
        out.append("{\n")
        value.fields.entries.forEachIndexed { index, (key, field) ->
            pad(out, indent + 1)
            writeString(out, key)
            out.append(": ")
            writeValue(out, field, indent + 1)
            if (index < value.fields.size - 1) out.append(',')
            out.append('\n')
        }
        pad(out, indent)
        out.append('}')
    }

    private fun writeArray(out: StringBuilder, value: JsonArray, indent: Int) {
        if (value.items.isEmpty()) {
            out.append("[]")
            return
        }
        if (value.items.none { it is JsonObject || it is JsonArray }) {
            out.append('[')
            value.items.forEachIndexed { index, item ->
                if (index > 0) out.append(", ")
                writeValue(out, item, indent)
            }
            out.append(']')
            return
        }
        out.append("[\n")
        value.items.forEachIndexed { index, item ->
            pad(out, indent + 1)
            writeValue(out, item, indent + 1)
            if (index < value.items.size - 1) out.append(',')
            out.append('\n')
        }
        pad(out, indent)
        out.append(']')
    }

    private fun pad(out: StringBuilder, indent: Int) {
        repeat(indent) { out.append("  ") }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u").append(char.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private const val HEX = 16
    private const val UNICODE_DIGITS = 4

    private class Parser(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[position] in " \t\r\n") position++
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting is too deep")
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (val char = text[position]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonString(stringValue())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (char == '-' || char.isAsciiDigit()) numberValue() else fail("unexpected '$char'")
            }
        }

        private fun objectValue(depth: Int): JsonObject {
            position++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a field name")
                val key = stringValue()
                skipWhitespace()
                expect(':')
                if (fields.put(key, value(depth + 1)) != null) fail("duplicate field \"$key\"")
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonObject(fields)
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(depth: Int): JsonArray {
            position++
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonArray(items)
            }
            while (true) {
                items += value(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonArray(items)
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> out.append(escape())
                    char < ' ' -> fail("unescaped control character in a string")
                    else -> out.append(char)
                }
            }
        }

        private fun escape(): Char = when (val char = next()) {
            '"', '\\', '/' -> char
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (position + UNICODE_DIGITS > text.length) fail("truncated unicode escape")
                val hex = text.substring(position, position + UNICODE_DIGITS)
                position += UNICODE_DIGITS
                hex.toIntOrNull(HEX)?.toChar() ?: fail("bad unicode escape")
            }
            else -> fail("bad escape '\\$char'")
        }

        private fun numberValue(): JsonNumber {
            val start = position
            if (peek() == '-') position++
            digits(allowLeadingZero = false)
            if (!atEnd() && text[position] == '.') {
                position++
                digits(allowLeadingZero = true)
            }
            if (!atEnd() && text[position] in "eE") {
                position++
                if (!atEnd() && text[position] in "+-") position++
                digits(allowLeadingZero = true)
            }
            val literal = text.substring(start, position)
            if (!literal.toDouble().isFinite()) fail("number out of range")
            return JsonNumber(literal)
        }

        private fun digits(allowLeadingZero: Boolean) {
            val start = position
            while (!atEnd() && text[position].isAsciiDigit()) position++
            if (position == start) fail("expected a digit")
            if (!allowLeadingZero && position - start > 1 && text[start] == '0') fail("leading zero")
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, position)) fail("unexpected literal")
            position += word.length
            return value
        }

        private fun peek(): Char = if (atEnd()) fail("unexpected end of input") else text[position]

        private fun next(): Char = peek().also { position++ }

        private fun expect(char: Char) {
            if (next() != char) fail("expected '$char'")
        }

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        private fun fail(message: String): Nothing = throw JsonFormatException("$message at offset $position")
    }
}
