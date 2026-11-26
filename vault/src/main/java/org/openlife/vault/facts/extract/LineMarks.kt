package org.openlife.vault.facts.extract

/* Code-point and space reads over a line (ADR-0008 §1; amendments E-06, E-08). */

/** A letter (any script, by code point) or an attached mark ends just before [index]. */
internal fun LineScan.letterBefore(index: Int): Boolean =
    index in 1..length && LineScan.isLetterLike(text.codePointBefore(index))

/** A letter (any script, by code point) or an attached mark starts at [index]. */
internal fun LineScan.letterAt(index: Int): Boolean =
    index in 0 until length && LineScan.isLetterLike(text.codePointAt(index))

/** Unclaimed space separators or tabs from [start], counting at most `MAX_SPACE_RUN` + 1 (amendment E-08). */
internal fun LineScan.spaceRun(start: Int): Int {
    var end = start
    while (end - start <= LineScan.MAX_SPACE_RUN && LineScan.isSpace(ch(end))) end++
    return end - start
}

/** Unclaimed space separators or tabs ending at [end], counting at most `MAX_SPACE_RUN` + 1. */
internal fun LineScan.spaceRunBefore(end: Int): Int {
    var start = end
    while (end - start <= LineScan.MAX_SPACE_RUN && LineScan.isSpace(ch(start - 1))) start--
    return end - start
}

/** End of the unclaimed run of format and combining marks from [start] (amendment E-06). */
internal fun LineScan.marksEnd(start: Int): Int {
    var end = start
    while (end < length && !isClaimed(end) && LineScan.isAttachedMark(text.codePointAt(end))) {
        end += Character.charCount(text.codePointAt(end))
    }
    return end
}
