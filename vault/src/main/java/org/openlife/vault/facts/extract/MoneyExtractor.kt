package org.openlife.vault.facts.extract

/** An amount being judged: `[start, end)` covers sign, markers and every digit group read. */
internal data class Amount(
    val start: Int,
    val end: Int,
    val prefix: Marker?,
    val suffix: Marker?,
    val readings: Readings,
    val negative: Boolean,
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

    /** A marker ending at [number] or one space before it; a sign may sit between them. */
    fun prefixOf(number: Dense): Marker? {
        val at = if (line.raw(number.start - 1) == '-') number.start - 1 else number.start
        val marker = markerByEnd[at] ?: markerByEnd[at - 1]?.takeIf { line.raw(at - 1) == ' ' && at == number.start }
        return marker?.takeIf { !line.anyClaimed(it.start, it.end) }
    }

    fun suffixOf(number: Dense): Marker? {
        val marker = markerByStart[number.end] ?: markerByStart[number.end + 1]?.takeIf { line.raw(number.end) == ' ' }
        return marker?.takeIf { !line.anyClaimed(it.start, it.end) }
    }

    fun numberStartingAt(at: Int): Dense? {
        val number = numberByStart[at] ?: numberByStart[at + 1]?.takeIf { line.raw(at) == ' ' }
        return number?.takeIf { !line.isClaimed(it.start) }
    }

    fun numberEndingAt(at: Int): Dense? {
        val number = numberByEnd[at] ?: numberByEnd[at - 1]?.takeIf { line.raw(at - 1) == ' ' }
        return number?.takeIf { !line.isClaimed(it.start) }
    }
}
