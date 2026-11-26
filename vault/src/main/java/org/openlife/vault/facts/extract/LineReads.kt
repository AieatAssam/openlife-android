package org.openlife.vault.facts.extract

/* Forward reads over a line's unclaimed text, and candidate emission (ADR-0008 §1, §2). */

/** Number of unclaimed ASCII digits from [start], counting at most [cap] + 1. */
internal fun LineScan.digits(start: Int, cap: Int): Int {
    var end = start
    while (end - start <= cap && LineScan.isAsciiDigit(ch(end))) end++
    return end - start
}

/** End of the unclaimed run of ASCII letters from [start], reading at most [cap] + 1 letters. */
internal fun LineScan.asciiWordEnd(start: Int, cap: Int): Int {
    var end = start
    while (end - start <= cap && LineScan.isAsciiLetter(ch(end))) end++
    return end
}

/** Number of unclaimed ASCII spaces from [start], counting at most [cap] + 1. */
internal fun LineScan.spaces(start: Int, cap: Int): Int {
    var end = start
    while (end - start <= cap && ch(end) == ' ') end++
    return end - start
}

/** End of the unclaimed non-whitespace run from [start]: where an abstention over an attached token stops. */
internal fun LineScan.tokenEnd(start: Int): Int {
    var end = start
    while (end < length && !isClaimed(end) && !Character.isWhitespace(text[end])) end++
    return end
}

/**
 * Emits one candidate after normalising its value set: duplicates
 * removed, ADR-0008 §2 order, a set of one stored as that value. A value
 * that cannot be written (too many entries, over 4,096 bytes, an
 * unpaired surrogate) is abstained, and still claims.
 */
internal fun LineScan.emit(output: LineScan.Output) {
    val values = output.values.distinct().sortedWith(LineScan.VALUE_ORDER)
    val writable = values.size <= ExtractedCandidate.MAX_CANDIDATES && values.none(::hasUnpairedSurrogate)
    val candidate = if (writable && values.isNotEmpty()) toCandidate(output, values) else null
    if (candidate == null || candidate.valueJson.toByteArray(Charsets.UTF_8).size > LineScan.MAX_VALUE_BYTES) {
        abstain(output.kind, output.start, output.end, AbstentionReason.LIMIT)
    } else {
        claim(output.start, output.end)
        candidates += candidate
    }
}

private fun hasUnpairedSurrogate(value: FactValue): Boolean =
    CanonicalJson.strings(value).any { it != null && CanonicalJson.hasUnpairedSurrogate(it) }

private fun LineScan.toCandidate(output: LineScan.Output, values: List<FactValue>) = ExtractedCandidate(
    kind = output.kind,
    rawText = text.substring(output.start, output.end),
    spanId = span.spanId,
    correctionId = span.correctionId,
    startOffset = output.start,
    endOffset = output.end,
    canonicalValue = values.singleOrNull(),
    ambiguity = if (values.size > 1) values else emptyList(),
    ruleId = output.rule.id,
    ruleVersion = output.rule.version,
    locale = if (output.english) "en" else "und",
    currencyContext = output.context,
)
