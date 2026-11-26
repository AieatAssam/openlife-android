package org.openlife.vault.facts.extract

/**
 * An amount being judged: `[start, end)` covers sign, markers and every
 * digit group read; [coreStart] is where it starts without a sign before
 * it. [signProblem] marks a sign §5.1 recognises but does not read.
 */
internal data class Amount(
    val start: Int,
    val end: Int,
    val prefix: Marker?,
    val suffix: Marker?,
    val readings: Readings,
    val negative: Boolean,
    val coreStart: Int = start,
    val signProblem: Boolean = false,
)

/**
 * `rules-money-v1` (ADR-0008 §5): a number is money only beside a marker.
 * Each dense number is judged once, left to right, with constant lookups
 * for its markers and neighbours.
 */
object MoneyExtractor {
    const val RULE_ID = "rules-money"
    const val RULES_VERSION = "v1"
    private val RULE = RuleRef(RULE_ID, RULES_VERSION)

    internal fun scan(line: LineScan, context: CurrencyContextIndex) {
        val markers = MoneyMarkers.markers(line)
        val numbers = MoneyMarkers.numbers(line)
        val scene = MoneyScene(line, markers, numbers)
        numbers.forEachIndexed { index, number ->
            line.reach(number.start)
            if (!line.isClaimed(number.start)) judge(scene, index, context)
        }
    }

    private fun judge(scene: MoneyScene, index: Int, context: CurrencyContextIndex) {
        val line = scene.line
        val number = scene.numbers[index]
        val prefix = scene.prefixOf(number)
        val suffix = scene.suffixOf(number)
        if (prefix == null && suffix == null) return
        val amount = MoneyShapes.amount(scene, index, prefix, suffix) ?: return
        val problem = MoneyProblems.problem(scene, amount)
        if (problem != null) {
            line.abstain(ExtractedKind.MONEY, problem.first, problem.second, problem.third)
            return
        }
        val reason = amount.readings.reason
        if (reason != null) {
            line.abstain(ExtractedKind.MONEY, amount.start, amount.end, reason)
            return
        }
        val options = MoneyCurrency.resolve(amount, line.span, context) ?: return
        val values = amount.readings.amounts.flatMap { reading ->
            val signed = if (amount.negative) "-$reading" else reading
            options.currencies.map { (currency, symbol) -> MoneyValue(signed, currency, symbol) }
        }
        if (options.conflict) {
            line.abstain(ExtractedKind.MONEY, amount.start, amount.end, AbstentionReason.MARKER_CONFLICT)
        } else {
            val kind = ExtractedKind.MONEY
            line.emit(LineScan.Output(kind, amount.start, amount.end, values, RULE, context = options.rows))
        }
    }
}

/**
 * The markers and numbers of one line, indexed by offset. Lookups skip
 * anything an earlier amount or rule has claimed since the index was built.
 */
internal class MoneyScene(val line: LineScan, markers: List<Marker>, val numbers: List<Dense>) {
    private val markerByStart = markers.associateBy { it.start }
    private val markerByEnd = markers.associateBy { it.end }
    private val numberByStart = numbers.associateBy { it.start }
    private val numberByEnd = numbers.associateBy { it.end }

    /**
     * A marker before [number]: directly or one space separator before it,
     * or across a dash-like sign and up to 4 space separators (§5.1 signs,
     * finding E-04; [MoneyShapes] judges the sign).
     */
    fun prefixOf(number: Dense): Marker? {
        val signed = LineScan.isDashLike(line.raw(number.start - 1))
        val at = if (signed) number.start - 1 else number.start
        val gap = line.spaceRunBefore(at)
        val marker = markerByEnd[at - gap]?.takeIf { gap <= if (signed) LineScan.MAX_SPACE_RUN else 1 }
        val owned = signed && marker != null && numberEndingAt(marker.start) != null
        return marker?.takeIf { !owned && !line.anyClaimed(it.start, it.end) }
    }

    /**
     * A marker after [number]: directly or one space separator after it, or
     * across a dash-like character. Across a sign, a marker that marks a
     * number on its other side belongs to that number (`€10–€20`).
     */
    fun suffixOf(number: Dense): Marker? {
        val signed = LineScan.isDashLike(line.raw(number.end))
        val at = if (signed) number.end + 1 else number.end
        val gap = line.spaceRun(at)
        val marker = markerByStart[at + gap]?.takeIf { gap <= if (signed) LineScan.MAX_SPACE_RUN else 1 }
        val owned = signed && marker != null && numberStartingAt(marker.end) != null
        return marker?.takeIf { !owned && !line.anyClaimed(it.start, it.end) }
    }

    /** An unclaimed marker starting at [at]. */
    fun markerAt(at: Int): Marker? = markerByStart[at]?.takeIf { !line.anyClaimed(it.start, it.end) }

    /** An unclaimed marker ending at [at]. */
    fun markerEndingAt(at: Int): Marker? = markerByEnd[at]?.takeIf { !line.anyClaimed(it.start, it.end) }

    /** A number starting at [at] or one space separator after it. */
    fun numberStartingAt(at: Int): Dense? {
        val number = numberByStart[at] ?: numberByStart[at + 1]?.takeIf { LineScan.isSpace(line.raw(at)) }
        return number?.takeIf { !line.isClaimed(it.start) }
    }

    /** A number ending at [at] or one space separator before it. */
    fun numberEndingAt(at: Int): Dense? {
        val number = numberByEnd[at] ?: numberByEnd[at - 1]?.takeIf { LineScan.isSpace(line.raw(at - 1)) }
        return number?.takeIf { !line.isClaimed(it.start) }
    }
}
