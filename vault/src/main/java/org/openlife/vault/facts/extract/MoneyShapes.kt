package org.openlife.vault.facts.extract

/**
 * The extent of an amount: §5.2 space groups and §5.1 signs. The forms
 * that abstain it are in [MoneyProblems].
 */
internal object MoneyShapes {
    private const val GROUP = 3

    fun amount(scene: MoneyScene, index: Int, prefix: Marker?, suffix: Marker?): Amount? {
        val line = scene.line
        val number = scene.numbers[index]
        if (prefix == null && !LineScan.isDashLike(line.raw(number.start - 1)) && !line.boundaryBefore(number.start)) {
            return null
        }
        if (suffix != null && prefix == null) groupedFraction(scene, number, suffix)?.let { return it }
        val first = if (suffix != null && prefix == null) leftChain(scene, index) else index
        val last = if (prefix != null && suffix == null) rightChain(scene, index) else index
        val readings = readings(scene, first, last, index)
        val digitsStart = scene.numbers[first].start
        val digitsEnd = scene.numbers[last].end
        val core = prefix?.start ?: digitsStart
        val before = (prefix?.end ?: digitsStart)..digitsStart
        val sign = MoneySigns.of(line, core, before, digitsEnd..(suffix?.start ?: digitsEnd))
        return Amount(sign.start, suffix?.end ?: digitsEnd, prefix, suffix, readings, sign.negative, core, sign.problem)
    }

    /** `1 234,50 €` with an ASCII space: a grouped number with a fraction is not read (§5.2). */
    private fun groupedFraction(scene: MoneyScene, number: Dense, suffix: Marker): Amount? {
        val before = scene.numberEndingAt(number.start)?.takeIf { it.pureDigits && it.text.length <= GROUP }
        if (before == null || !hasFraction(number)) return null
        val ambiguous = Readings(emptyList(), AbstentionReason.AMBIGUOUS_FORM)
        return Amount(before.start, suffix.end, null, suffix, ambiguous, false)
    }

    private fun hasFraction(number: Dense): Boolean {
        val mark = number.text.indexOfFirst { it == '.' || it == ',' }
        return mark == GROUP && number.text.take(GROUP).all(LineScan::isAsciiDigit)
    }

    /** The adjacent group, and the whole space-grouped number when there is one. */
    private fun readings(scene: MoneyScene, first: Int, last: Int, adjacent: Int): Readings {
        val own = MoneyNumbers.read(scene.numbers[adjacent].text)
        if (first == last) return own
        val joined = MoneyNumbers.joined((first..last).map { scene.numbers[it].text })
        return Readings(own.amounts + joined.amounts, own.reason ?: joined.reason)
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
            left.end + 1 == right.start && scene.line.ch(left.end) == ' ' && !scene.line.isClaimed(left.start)
}

/** Where an amount's sign starts, whether it is read as negative, and whether §5.1 abstains it. */
internal data class Sign(val start: Int, val negative: Boolean, val problem: Boolean)

/**
 * ADR-0008 §5.1 signs (finding E-04). Only a lone ASCII `-` touching the
 * marker or the digits is read (`-€5`, `€-5`, `-5 €`). Any other hyphen,
 * dash or minus look-alike, a sign with spaces beside it (`- €5`, `EUR -5`),
 * a dash between the digits and a marker after them, or two signs abstain.
 * A dash after a digit is a range or a minus between numbers, never a sign.
 */
internal object MoneySigns {
    private const val READ = "-"

    fun of(line: LineScan, core: Int, beforeDigits: IntRange, afterDigits: IntRange): Sign {
        val inner = between(line, beforeDigits) + between(line, afterDigits)
        val dash = core - 1 - line.spaceRunBefore(core)
        val outer = LineScan.isDashLike(line.ch(dash)) && core - dash <= LineScan.MAX_SPACE_RUN + 1 &&
            !digitBefore(line, dash)
        val signs = if (outer) inner + line.text[dash] else inner
        val spaced = outer && dash < core - 1
        val start = if (outer) dash else core
        return when {
            signs.isEmpty() -> Sign(start, negative = false, problem = false)
            signs == READ && !spaced -> Sign(start, negative = true, problem = false)
            else -> Sign(start, negative = false, problem = true)
        }
    }

    /** The dash-like characters in `[range.first, range.last)`; a lone `-` stays readable, anything else does not. */
    private fun between(line: LineScan, range: IntRange): String {
        val text = line.text.substring(range.first, range.last)
        val dashes = text.filter(LineScan::isDashLike)
        return if (dashes.isEmpty() || text == READ) dashes else "$dashes?"
    }

    private fun digitBefore(line: LineScan, at: Int): Boolean =
        LineScan.isAsciiDigit(line.raw(at - 1)) || (line.raw(at - 1) == ' ' && LineScan.isAsciiDigit(line.raw(at - 2)))
}
