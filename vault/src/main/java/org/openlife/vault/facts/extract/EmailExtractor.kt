package org.openlife.vault.facts.extract

/**
 * `rules-email-v1` (ADR-0008 §6.2). Recognised at an `@` with a local part;
 * the local part is kept as printed and the domain lowercased.
 */
object EmailExtractor {
    const val RULE_ID = "rules-email"
    const val RULES_VERSION = "v1"
    private val RULE = RuleRef(RULE_ID, RULES_VERSION)

    private const val MAX_LOCAL = 64
    private const val MAX_TOTAL = 254
    private const val MAX_QUOTED = 64
    private const val MIN_TOP_LABEL = 2
    private const val MAILTO = "mailto:"
    private const val LOCAL_PUNCTUATION = "._%+-"
    private const val AFTER = "@_"
    private const val PUNYCODE = "xn--"

    internal fun scan(line: LineScan) {
        var index = 0
        while (index < line.length) {
            line.reach(index)
            index = if (line.ch(index) == '@') judge(line, index) else index + 1
        }
    }

    /** Judges the `@` at [at]; returns where scanning continues. */
    private fun judge(line: LineScan, at: Int): Int {
        var localStart = at
        while (isLocalChar(line.ch(localStart - 1))) localStart--
        val domainEnd = domainEnd(line, at + 1)
        if (localStart == at) {
            val quote = quotedStart(line, at)
            if (quote != null) line.abstain(ExtractedKind.EMAIL, quote, domainEnd, AbstentionReason.INVALID_VALUE)
            return maxOf(at + 1, domainEnd)
        }
        val mailto = localStart - MAILTO.length
        val start = if (line.matchesIgnoreCase(mailto, MAILTO)) mailto else localStart
        val local = line.text.substring(localStart, at)
        val domain = line.text.substring(at + 1, domainEnd)
        val attached = !line.boundaryBefore(start) || !line.boundaryAfter(domainEnd) || line.raw(domainEnd) in AFTER
        val reason = when {
            attached -> AbstentionReason.ATTACHED_CHARACTERS
            !validLocal(local) -> AbstentionReason.INVALID_VALUE
            !validDomain(domain) -> AbstentionReason.HOST
            local.length + 1 + domain.length > MAX_TOTAL -> AbstentionReason.LIMIT
            else -> null
        }
        if (reason != null) {
            val end = if (attached) maxOf(domainEnd, line.tokenEnd(domainEnd)) else domainEnd
            line.abstain(ExtractedKind.EMAIL, start, end, reason)
            return maxOf(at + 1, end)
        }
        val normalized = local + "@" + domain.lowercase()
        val value = TextValue(ExtractedKind.EMAIL, normalized, line.text.substring(start, domainEnd))
        line.emit(LineScan.Output(ExtractedKind.EMAIL, start, domainEnd, listOf(value), RULE))
        return domainEnd
    }

    /** The domain run after `@`, without a trailing sentence dot. */
    private fun domainEnd(line: LineScan, from: Int): Int {
        var end = from
        while (isDomainChar(line.ch(end))) end++
        while (end > from && line.text[end - 1] == '.') end--
        return end
    }

    /** A quoted local part (`"a b"@…`) is recognised and abstained; returns its opening quote. */
    private fun quotedStart(line: LineScan, at: Int): Int? {
        if (line.ch(at - 1) != '"') return null
        var index = at - 2
        while (insideQuote(line, index, at)) index--
        return if (index >= 0 && line.ch(index) == '"') index else null
    }

    private fun insideQuote(line: LineScan, index: Int, at: Int): Boolean {
        val open = index >= 0 && at - index <= MAX_QUOTED
        return open && line.ch(index) != '"' && !line.isClaimed(index)
    }

    private fun validLocal(local: String): Boolean =
        local.length <= MAX_LOCAL && !local.startsWith('.') && !local.endsWith('.') && ".." !in local

    private fun validDomain(domain: String): Boolean {
        val labels = domain.split('.')
        val top = labels.last()
        return labels.size >= 2 && labels.all(::validLabel) && top.length >= MIN_TOP_LABEL &&
            top.all(LineScan::isAsciiLetter)
    }

    /** ASCII letters, digits and inner hyphens; never an IDNA `xn--` label (finding E-10). */
    private fun validLabel(label: String): Boolean =
        label.isNotEmpty() && label.all(::isLabelChar) && !label.startsWith('-') && !label.endsWith('-') &&
            !label.startsWith(PUNYCODE, ignoreCase = true)

    private fun isLocalChar(char: Char): Boolean =
        LineScan.isAsciiLetter(char) || LineScan.isAsciiDigit(char) || char in LOCAL_PUNCTUATION

    private fun isDomainChar(char: Char): Boolean = isLabelChar(char) || char == '.'

    private fun isLabelChar(char: Char): Boolean =
        LineScan.isAsciiLetter(char) || LineScan.isAsciiDigit(char) || char == '-'
}
