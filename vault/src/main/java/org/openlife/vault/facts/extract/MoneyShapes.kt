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
        val signAt = number.start - 1
        if (prefix == null && line.raw(signAt) != '-' && !line.boundaryBefore(number.start)) return null
        if (suffix != null && prefix == null) groupedFraction(scene, number, suffix)?.let { return it }
        val first = if (suffix != null && prefix == null) leftChain(scene, index) else index
        val last = if (prefix != null && suffix == null) rightChain(scene, index) else index
        val readings = readings(scene, first, last, index)
        val digitsStart = scene.numbers[first].start
        val (start, negative) = sign(line, prefix?.start ?: digitsStart, prefix != null && line.raw(prefix.end) == '-')
        return Amount(start, suffix?.end ?: scene.numbers[last].end, prefix, suffix, readings, negative)
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

    /** A `-` directly before the amount, not after a digit (§5.1). */
    private fun sign(line: LineScan, start: Int, between: Boolean): Pair<Int, Boolean> {
        if (between) return start to true
        val dash = start - 1
        val signed = line.ch(dash) == '-' && !digitBefore(line, dash)
        return if (signed) dash to true else start to false
    }

    private fun digitBefore(line: LineScan, at: Int): Boolean =
        LineScan.isAsciiDigit(line.raw(at - 1)) || (line.raw(at - 1) == ' ' && LineScan.isAsciiDigit(line.raw(at - 2)))
}
