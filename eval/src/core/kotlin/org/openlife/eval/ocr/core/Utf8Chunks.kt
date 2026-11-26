package org.openlife.eval.ocr.core

/** Splitting for logcat, whose entries hold about 4 KB of UTF-8 (P2-04 review X-07). */
object Utf8Chunks {
    /** Pieces of at most [limit] UTF-8 bytes, split only between code points; joined, they are [text]. */
    fun split(text: String, limit: Int): List<String> {
        require(limit >= MAX_CODE_POINT_BYTES) { "a chunk must hold any code point" }
        val chunks = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) {
                chunks += chunk.toString()
                chunk.setLength(0)
                bytes = 0
            }
            chunk.appendCodePoint(codePoint)
            bytes += size
            index += Character.charCount(codePoint)
        }
        if (chunk.isNotEmpty()) chunks += chunk.toString()
        return chunks
    }

    private const val MAX_CODE_POINT_BYTES = 4
}
