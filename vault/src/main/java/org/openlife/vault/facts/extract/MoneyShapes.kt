package org.openlife.vault.facts.extract

/**
 * The extent of an amount: §5.2 space groups and §5.1 signs. The forms
 * that abstain it are in [MoneyProblems].
 */
internal object MoneyShapes {
    private const val GROUP = 3
    private val AMBIGUOUS = Readings(emptyList(), AbstentionReason.AMBIGUOUS_FORM)

    fun amount(scene: MoneyScene, index: Int, prefix: Marker?, suffix: Marker?): Amount? {
        val line = scene.line
        val number = scene.numbers[index]
        if (prefix == null && !LineScan.isDashLike(line.raw(number.start - 1)) && !line.boundaryBefore(number.start)) {
            return null
        }
        val (first, last, fraction) = extent(scene, index, prefix, suffix)
        val readings = if (fraction) AMBIGUOUS else readings(scene, first, last, index)
        val digitsStart = scene.numbers[first].start
        val digitsEnd = scene.numbers[last].end
        val core = prefix?.start ?: digitsStart
        val before = (prefix?.end ?: digitsStart)..digitsStart
        val sign = MoneySigns.of(line, core, before, digitsEnd..(suffix?.start ?: digitsEnd))
        return Amount(sign.start, suffix?.end ?: digitsEnd, prefix, suffix, readings, sign.negative, core, sign.problem)
    }

    /**
     * The first and last digit groups read, and whether a fraction follows a
     * space-grouped number. A marker after the digits reads groups to its
     * left, one before it to its right (§5.2). `1 234,50 €` and `€1 234,50`
     * alike end in a group with a fraction: the whole run abstains (findings
     * E-01, EE-02).
     */
    private fun extent(scene: MoneyScene, index: Int, prefix: Marker?, suffix: Marker?): Triple<Int, Int, Boolean> {
        val numbers = scene.numbers
        val fractionBefore = index > 0 && joinsFraction(scene, numbers[index - 1], numbers[index])
        return when {
            suffix != null && prefix == null && fractionBefore -> Triple(leftChain(scene, index - 1), index, true)
            suffix != null && prefix == null -> Triple(leftChain(scene, index), index, false)
            prefix != null && suffix == null -> rightExtent(scene, index)
            else -> Triple(index, index, false)
        }
    }

    private fun rightExtent(scene: MoneyScene, index: Int): Triple<Int, Int, Boolean> {
        val last = rightChain(scene, index)
        val next = scene.numbers.getOrNull(last + 1)
        val fraction = next != null && joinsFraction(scene, scene.numbers[last], next)
        return Triple(index, if (fraction) last + 1 else last, fraction)
    }

    /** A group of at most 3 digits, one space separator, then 3 digits with a fraction (`5 000.00`). */
    private fun joinsFraction(scene: MoneyScene, left: Dense, right: Dense): Boolean {
        val mark = right.text.indexOfFirst { it == '.' || it == ',' }
        val fraction = mark == GROUP && right.text.take(GROUP).all(LineScan::isAsciiDigit)
        return fraction && left.pureDigits && left.text.length <= GROUP && spaced(scene, left, right)
    }

    /**
     * The adjacent group, and the whole space-grouped number when there is
     * one. A grouped number led by a `0` group is abstained (`€0 001`). A
     * zero-led adjacent group is never a reading of its own; the first
     * group stands in for it (`1 000 €` gives `1 | 1000`; finding EE-06).
     */
    private fun readings(scene: MoneyScene, first: Int, last: Int, adjacent: Int): Readings {
        val own = MoneyNumbers.read(scene.numbers[adjacent].text)
        if (first == last) return own
        val groups = (first..last).map { scene.numbers[it].text }
        if (groups.first().startsWith('0')) return AMBIGUOUS
        val alone = if (groups[adjacent - first].startsWith('0')) MoneyNumbers.read(groups.first()) else own
        val joined = MoneyNumbers.joined(groups)
        return Readings(alone.amounts + joined.amounts, alone.reason ?: joined.reason)
    }

    /** Groups before a marker after the digits: each later group exactly 3 digits, one space apart. */
    private fun leftChain(scene: MoneyScene, index: Int): Int {
        var at = index
        while (at > 0 && links(scene, scene.numbers[at - 1], scene.numbers[at])) at--
        return at
    }

    private fun rightChain(scene: MoneyScene, index: Int): Int {
        if (scene.numbers[index].text.length > GROUP) return index
        var at = index
        while (at + 1 < scene.numbers.size && links(scene, scene.numbers[at], scene.numbers[at + 1])) at++
        return at
    }

    private fun links(scene: MoneyScene, left: Dense, right: Dense): Boolean =
        left.pureDigits && right.pureDigits && left.text.length <= GROUP && right.text.length == GROUP &&
            spaced(scene, left, right)

    /** Exactly one space separator or tab between the groups (finding E-08: any space splits like ASCII). */
    private fun spaced(scene: MoneyScene, left: Dense, right: Dense): Boolean =
        left.end + 1 == right.start && LineScan.isSpace(scene.line.ch(left.end)) && !scene.line.isClaimed(left.start)
}

/** Where an amount's sign starts, whether it is read as negative, and whether §5.1 abstains it. */
internal data class Sign(val start: Int, val negative: Boolean, val problem: Boolean)

/**
 * ADR-0008 §5.1 signs (finding E-04). Only a lone ASCII `-` touching the
 * marker or the digits is read (`-€5`, `€-5`, `-5 €`). Any other hyphen,
 * dash or minus look-alike, a sign with spaces beside it (`- €5`, `EUR -5`),
 * a dash between the digits and a marker after them, two signs (`--€5`)
 * or `±` abstain.
 * A dash after a digit is a range or a minus between numbers, never a sign.
 */
internal object MoneySigns {
    private const val READ = "-"
    private const val PLUS_MINUS = '\u00B1'

    fun of(line: LineScan, core: Int, beforeDigits: IntRange, afterDigits: IntRange): Sign {
        val inner = between(line, beforeDigits) + between(line, afterDigits)
        val dash = core - 1 - line.spaceRunBefore(core)
        val outer = isSign(line.ch(dash)) && core - dash <= LineScan.MAX_SPACE_RUN + 1 && !digitBefore(line, dash)
        val signs = if (outer) inner + line.text[dash] else inner
        val doubled = outer && LineScan.isSignLike(line.ch(dash - 1))
        val spaced = outer && (dash < core - 1 || doubled)
        val attached = (outer && letterBefore(line, dash)) || letterDashBefore(line, beforeDigits)
        val start = when {
            doubled -> dash - 1
            outer -> dash
            else -> core
        }
        return when {
            signs.isEmpty() -> Sign(start, negative = false, problem = false)
            signs == READ && !spaced && !attached -> Sign(start, negative = true, problem = false)
            else -> Sign(start, negative = false, problem = true)
        }
    }

    /** A dash-like character or `±` in sign position; only a lone `-` is read (finding EE-05). */
    private fun isSign(char: Char): Boolean = LineScan.isDashLike(char) || char == PLUS_MINUS

    /**
     * A dash attached to a preceding letter (`Coffee-€3.50`, `EUR-5.00`) is a
     * separator in running text, not a sign (proposed A-14). The sign is
     * unclaimed, so the amount abstains rather than read as negative.
     */
    private fun letterDashBefore(line: LineScan, range: IntRange): Boolean =
        (range.first until range.last).any { LineScan.isDashLike(line.ch(it)) && letterBefore(line, it) }

    private fun letterBefore(line: LineScan, at: Int): Boolean =
        at > 0 && at <= line.length && LineScan.isLetterLike(line.text.codePointBefore(at))

    /** The dash-like characters in `[range.first, range.last)`; a lone `-` stays readable, anything else does not. */
    private fun between(line: LineScan, range: IntRange): String {
        val text = line.text.substring(range.first, range.last)
        val dashes = text.filter(LineScan::isDashLike)
        return if (dashes.isEmpty() || text == READ) dashes else "$dashes?"
    }

    private fun digitBefore(line: LineScan, at: Int): Boolean =
        LineScan.isAsciiDigit(line.raw(at - 1)) || (line.raw(at - 1) == ' ' && LineScan.isAsciiDigit(line.raw(at - 2)))
}
