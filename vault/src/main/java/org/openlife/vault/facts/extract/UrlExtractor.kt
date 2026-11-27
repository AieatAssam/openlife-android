package org.openlife.vault.facts.extract

/**
 * `rules-url-v1` (ADR-0008 §6.1): a hand-written scanner with a narrow
 * grammar. Nothing is resolved, decoded, opened or rewritten beyond
 * lowercasing the scheme and host (P3-03-R4, C2-R47).
 */
object UrlExtractor {
    const val RULE_ID = "rules-url"
    const val RULES_VERSION = "v1"
    private val RULE = RuleRef(RULE_ID, RULES_VERSION)

    private const val MAX_LENGTH = 2_048
    private const val MAX_PORT_DIGITS = 5
    private const val MAX_SCHEME = 32
    private const val WWW = "www."
    private const val PUNYCODE = "xn--"
    private val WEB_SCHEMES = listOf("https://", "http://")
    private val OPAQUE_SCHEMES = listOf(
        "javascript:", "vbscript:", "data:", "file:", "intent:", "ftp:", "blob:", "content:", "about:",
    )
    private const val URL_PUNCTUATION = "-._~:/?#[]@!$&'()*+,;=%"
    private const val TRAILING_TRIM = ".,;:!?'\""
    private val CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{')

    internal fun scan(line: LineScan) {
        var index = 0
        while (index < line.length) {
            line.reach(index)
            index = if (startsWord(line, index)) scanWord(line, index) else index + 1
        }
    }

    private fun startsWord(line: LineScan, index: Int): Boolean =
        LineScan.isAsciiLetter(line.ch(index)) && line.boundaryBefore(index)

    /** Judges the word at [start]; returns where scanning continues. */
    private fun scanWord(line: LineScan, start: Int): Int {
        val web = WEB_SCHEMES.firstOrNull { line.matchesIgnoreCase(start, it) }
        return when {
            web != null -> judge(line, start, start + web.length, web.length - "://".length)
            line.matchesIgnoreCase(start, WWW) -> judge(line, start, start, 0)
            else -> otherScheme(line, start) ?: line.asciiWordEnd(start, Int.MAX_VALUE - 1)
        }
    }

    /** Other schemes are recognised, abstained and claimed (ADR-0008 §6.1). */
    private fun otherScheme(line: LineScan, start: Int): Int? {
        var end = start
        while (end - start < MAX_SCHEME && isSchemeChar(line.ch(end))) end++
        if (line.ch(end) != ':') return null
        val slashes = line.ch(end) == ':' && line.ch(end + 1) == '/' && line.ch(end + 2) == '/'
        val opaque = OPAQUE_SCHEMES.any { line.matchesIgnoreCase(start, it) } && isUrlChar(line.ch(end + 1))
        if (!slashes && !opaque) return null
        val extent = trimmedEnd(line, start, extentEnd(line, end + 1))
        line.abstain(ExtractedKind.URL, start, extent, AbstentionReason.SCHEME)
        return extent
    }

    private fun judge(line: LineScan, start: Int, hostStart: Int, schemeLength: Int): Int {
        val end = trimmedEnd(line, start, extentEnd(line, hostStart))
        val reason = when {
            !line.boundaryAfter(end) -> AbstentionReason.HOST
            end - start > MAX_LENGTH -> AbstentionReason.LIMIT
            else -> authorityProblem(line.text.substring(hostStart, end))
        }
        if (reason != null) {
            val claimEnd = if (reason == AbstentionReason.HOST) maxOf(end, line.tokenEnd(end)) else end
            line.abstain(ExtractedKind.URL, start, claimEnd, reason)
            return claimEnd
        }
        val raw = line.text.substring(start, end)
        val hostEnd = hostStart + authorityLength(line.text.substring(hostStart, end))
        val normalized = raw.substring(0, schemeLength).lowercase() + raw.substring(schemeLength, hostStart - start) +
            line.text.substring(hostStart, hostEnd).lowercase() + line.text.substring(hostEnd, end)
        val value = TextValue(ExtractedKind.URL, normalized, raw)
        line.emit(LineScan.Output(ExtractedKind.URL, start, end, listOf(value), RULE))
        return end
    }

    private fun extentEnd(line: LineScan, from: Int): Int {
        var end = from
        while (isUrlChar(line.ch(end))) end++
        return end
    }

    /** Trims trailing punctuation and unmatched closing brackets, counting brackets once (linear). */
    private fun trimmedEnd(line: LineScan, start: Int, extentEnd: Int): Int {
        val balance = HashMap<Char, Int>()
        for (index in start until extentEnd) {
            val char = line.text[index]
            CLOSERS[char]?.let { balance[it] = (balance[it] ?: 0) - 1 }
            if (char in CLOSERS.values) balance[char] = (balance[char] ?: 0) + 1
        }
        var end = extentEnd
        while (end > start) {
            val last = line.text[end - 1]
            val opener = CLOSERS[last]
            val unmatched = opener != null && (balance[opener] ?: 0) < 0
            if (last !in TRAILING_TRIM && !unmatched) break
            if (opener != null) balance[opener] = (balance[opener] ?: 0) + 1
            end--
        }
        return end
    }

    /** Null when the authority is an ASCII host of 2+ labels with an optional 1-5 digit port. */
    private fun authorityProblem(afterScheme: String): AbstentionReason? {
        val authority = afterScheme.substring(0, authorityLength(afterScheme))
        if ('@' in authority) return AbstentionReason.USERINFO
        val host = authority.substringBefore(':')
        val port = if (':' in authority) authority.substringAfter(':') else null
        val labels = host.split('.')
        val badHost = labels.size < 2 || labels.any { label -> label.isEmpty() || !label.all(::isHostChar) } ||
            labels.any { it.startsWith(PUNYCODE, ignoreCase = true) }
        val badPort = port != null && (port.length !in 1..MAX_PORT_DIGITS || !port.all(LineScan::isAsciiDigit))
        return if (badHost || badPort) AbstentionReason.HOST else null
    }

    private fun authorityLength(afterScheme: String): Int {
        val stop = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (stop < 0) afterScheme.length else stop
    }

    private fun isSchemeChar(char: Char): Boolean =
        LineScan.isAsciiLetter(char) || LineScan.isAsciiDigit(char) || char == '+' || char == '.' || char == '-'

    private fun isHostChar(char: Char): Boolean =
        LineScan.isAsciiLetter(char) || LineScan.isAsciiDigit(char) || char == '-'

    internal fun isUrlChar(char: Char): Boolean =
        LineScan.isAsciiLetter(char) || LineScan.isAsciiDigit(char) || char in URL_PUNCTUATION
}
