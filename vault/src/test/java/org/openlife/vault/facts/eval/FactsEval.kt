package org.openlife.vault.facts.eval

import org.openlife.vault.facts.extract.ExtractedCandidate
import org.openlife.vault.facts.extract.ExtractedKind
import org.openlife.vault.facts.extract.ExtractionSpan
import org.openlife.vault.facts.extract.FactExtractor
import org.openlife.vault.facts.extract.MoneyValue
import java.io.File

/** A count of hits out of a denominator. A zero denominator has no value and fails the gate. */
internal data class Rate(val hits: Int, val total: Int) {
    val value: Double? get() = if (total == 0) null else hits.toDouble() / total
}

/** How one emitted candidate compares with the expected items of its row. */
internal enum class Outcome { EXACT, SUBSET, SUPERSET, WRONG_VALUE, UNEXPECTED }

/** How one expected item was recovered: exactly, wrongly, abstained on, or silently missed. */
internal enum class UnitStatus { EXACT, WRONG, ABSTAINED, SILENT }

/**
 * The measured facts report (P3-06-R1). Keys are gated families; DATE covers DATE, TIME and
 * DATETIME (Q-3). Precision is hits over every emitted candidate of the family; recall is exact
 * hits over every expected item (a clarification is one item).
 */
internal data class FactsReport(
    val precision: Map<String, Rate>,
    val recall: Map<String, Rate>,
    val precisionByRule: Map<String, Rate>,
    val ambiguousDmFlagged: Rate,
    val bareCurrencyInferred: Int,
    val wrongActionableDates: Int,
    val abstentionExpected: Rate,
    val unexpectedAbstentions: Int,
    val rawSliceMismatches: Int,
    val outcomes: Map<Outcome, Int>,
    val unitStatuses: Map<UnitStatus, Int>,
)

/** One row's scored result, with the lines that go into the disagreement dump. */
internal class RowResult(
    val emitted: List<EmittedRow>,
    val units: List<UnitRow>,
    val abstentionHits: Int,
    val abstentionTotal: Int,
    val unexpected: List<String>,
    val missing: List<String>,
    val dmFlagged: Boolean?,
)

internal data class EmittedRow(
    val family: String,
    val rule: String,
    val outcome: Outcome,
    val definite: Boolean,
    val bare: Boolean,
    val rawSliceOk: Boolean,
    val line: String,
)

internal data class UnitRow(val family: String, val status: UnitStatus, val line: String)

private enum class UnitState { OPEN, EXACT, WRONG }

/** One expected group: a definite value (one canonical) or a clarification (several readings). */
private data class ExpectedUnit(
    val kind: ExtractedKind,
    val raw: String,
    val canonicals: Set<String>,
    val clarification: Boolean,
)

/** Scores eval/facts/v1/text.tsv against [FactExtractor.extract], one span per row. */
internal object FactsEval {
    private const val DATE_FAMILY = "DATE"
    private const val FACTS_PATH = "eval/facts/v1/text.tsv"
    private const val DUMP_PATH = "vault/build/facts-eval/rows.tsv"
    private const val SUMMARY_PATH = "vault/build/facts-eval/summary.tsv"
    private const val DUMP_HEADER = "row\toutcome\tfamily\trule\tkind\traw\tcanonical"
    private val GATED = listOf("DATE", "MONEY", "URL", "EMAIL", "PHONE")
    private val MULTI_CODE_SYMBOLS = setOf("$", "£", "¥", "₩", "kr")
    private val ISO_CODE = Regex("[A-Z]{3}")

    fun measure(): FactsReport {
        val root = File(System.getProperty("openlife.rootDir") ?: "..")
        val results = FactsFixture.load(File(root, FACTS_PATH)).map(::scoreRow)
        writeDump(File(root, DUMP_PATH), results)
        return aggregate(results).also { writeSummary(File(root, SUMMARY_PATH), it) }
    }

    internal fun scoreRow(row: FactRow): RowResult {
        val result = FactExtractor.extract(listOf(ExtractionSpan(row.id, null, 0, row.text)))
        val units = expectedUnits(row.items)
        val states = Array(units.size) { UnitState.OPEN }
        val emitted = result.candidates.map { emit(row, it, units, states) }
        val abstained = result.abstentions.map { it.kind to slice(row.text, it.startOffset, it.endOffset) }
        val unitRows = units.mapIndexed { i, unit -> unitRow(row.id, unit, states[i], abstained) }
        val expected = row.abstain.map { it.kind to it.raw }
        return RowResult(
            emitted = emitted,
            units = unitRows,
            abstentionHits = expected.count { it in abstained },
            abstentionTotal = expected.size,
            unexpected = abstained.filter { it !in expected }.map {
                "${row.id}\tUNEXPECTED-ABSTAIN\t-\t-\t${it.first?.name ?: "*"}\t${it.second}\t-"
            },
            missing = expected.filter { it !in abstained }.map {
                "${row.id}\tMISSING-ABSTAIN\t-\t-\t${it.first?.name ?: "*"}\t${it.second}\t-"
            },
            dmFlagged = dmFlag(row, result.candidates),
        )
    }

    internal fun aggregate(results: List<RowResult>): FactsReport {
        val emitted = results.flatMap { it.emitted }
        val units = results.flatMap { it.units }
        return FactsReport(
            precision = GATED.associateWith { family ->
                rate(emitted.filter { it.family == family }) { it.outcome == Outcome.EXACT }
            },
            recall = GATED.associateWith { family ->
                rate(units.filter { it.family == family }) { it.status == UnitStatus.EXACT }
            },
            precisionByRule = emitted.groupBy { it.rule }.mapValues { (_, list) ->
                rate(list) { it.outcome == Outcome.EXACT }
            },
            ambiguousDmFlagged = rate(results.filter { it.dmFlagged != null }) { it.dmFlagged == true },
            bareCurrencyInferred = emitted.count { it.bare },
            wrongActionableDates = emitted.count {
                it.family == DATE_FAMILY && it.definite && it.outcome != Outcome.EXACT
            },
            abstentionExpected = Rate(results.sumOf { it.abstentionHits }, results.sumOf { it.abstentionTotal }),
            unexpectedAbstentions = results.sumOf { it.unexpected.size },
            rawSliceMismatches = emitted.count { !it.rawSliceOk },
            outcomes = emitted.groupingBy { it.outcome }.eachCount(),
            unitStatuses = units.groupingBy { it.status }.eachCount(),
        )
    }

    private fun <T> rate(items: List<T>, hit: (T) -> Boolean): Rate = Rate(items.count(hit), items.size)

    private fun emit(
        row: FactRow,
        cand: ExtractedCandidate,
        units: List<ExpectedUnit>,
        states: Array<UnitState>,
    ): EmittedRow {
        val outcome = match(cand, readingsOf(cand), units, states)
        val family = familyOf(cand.kind)
        val rule = "${cand.ruleId}-${cand.ruleVersion}"
        val line = listOf(row.id, outcome.name, family, rule, cand.kind.name, cand.rawText, cand.canonicalText)
            .joinToString("\t")
        return EmittedRow(
            family = family,
            rule = rule,
            outcome = outcome,
            definite = cand.canonicalValue != null,
            bare = bareCurrency(cand),
            rawSliceOk = slice(row.text, cand.startOffset, cand.endOffset) == cand.rawText,
            line = line,
        )
    }

    private fun match(
        cand: ExtractedCandidate,
        readings: Set<String>,
        units: List<ExpectedUnit>,
        states: Array<UnitState>,
    ): Outcome {
        val idx = units.indices.firstOrNull { i ->
            states[i] == UnitState.OPEN && units[i].kind == cand.kind && units[i].raw == cand.rawText
        } ?: return Outcome.UNEXPECTED
        val unit = units[idx]
        if (cand.needsClarification == unit.clarification && readings == unit.canonicals) {
            states[idx] = UnitState.EXACT
            return Outcome.EXACT
        }
        states[idx] = UnitState.WRONG
        return when {
            !cand.needsClarification || !unit.clarification -> Outcome.WRONG_VALUE
            readings.containsAll(unit.canonicals) -> Outcome.SUPERSET
            unit.canonicals.containsAll(readings) -> Outcome.SUBSET
            else -> Outcome.WRONG_VALUE
        }
    }

    private fun unitRow(
        rowId: String,
        unit: ExpectedUnit,
        state: UnitState,
        abstained: List<Pair<ExtractedKind?, String>>,
    ): UnitRow {
        val status = when (state) {
            UnitState.EXACT -> UnitStatus.EXACT
            UnitState.WRONG -> UnitStatus.WRONG
            UnitState.OPEN -> if ((unit.kind to unit.raw) in abstained) UnitStatus.ABSTAINED else UnitStatus.SILENT
        }
        val family = familyOf(unit.kind)
        val canonicals = unit.canonicals.sorted().joinToString(" | ")
        val line = listOf(rowId, "UNIT-${status.name}", family, "-", unit.kind.name, unit.raw, canonicals)
            .joinToString("\t")
        return UnitRow(family, status, line)
    }

    private fun expectedUnits(items: List<ExpectedItem>): List<ExpectedUnit> =
        items.groupBy { it.kind to it.raw }.map { (key, group) ->
            ExpectedUnit(key.first, key.second, group.map { it.canonical }.toSet(), group.size > 1)
        }

    private fun dmFlag(row: FactRow, candidates: List<ExtractedCandidate>): Boolean? {
        val truth = row.truth ?: return null
        return candidates.any { it.needsClarification && it.kind == ExtractedKind.DATE && truth in readingsOf(it) }
    }

    /** A MONEY value that took a currency from a multi-code symbol with no code and no context (ADR-0008 §5.4). */
    private fun bareCurrency(cand: ExtractedCandidate): Boolean {
        val money = cand.canonicalValue as? MoneyValue ?: return false
        val coded = ISO_CODE.containsMatchIn(cand.rawText) || cand.currencyContext.any { it.codeStart != null }
        return money.currency != null && (money.rawSymbol ?: "") in MULTI_CODE_SYMBOLS && !coded
    }

    private fun readingsOf(cand: ExtractedCandidate): Set<String> =
        cand.canonicalValue?.let { setOf(it.canonicalText()) } ?: cand.ambiguity.map { it.canonicalText() }.toSet()

    internal fun familyOf(kind: ExtractedKind): String = when (kind) {
        ExtractedKind.DATE, ExtractedKind.TIME, ExtractedKind.DATETIME -> DATE_FAMILY
        else -> kind.name
    }

    private fun slice(text: String, start: Int, end: Int): String =
        if (start in 0 until end && end <= text.length) text.substring(start, end) else ""

    private fun writeDump(file: File, results: List<RowResult>) {
        file.parentFile.mkdirs()
        val lines = results.flatMap { r ->
            r.emitted.map { it.line } + r.units.map { it.line } + r.unexpected + r.missing
        }
        file.writeText((listOf(DUMP_HEADER) + lines).joinToString("\n", postfix = "\n"))
    }

    private fun writeSummary(file: File, report: FactsReport) {
        file.parentFile.mkdirs()
        val lines = mutableListOf<String>()
        report.precision.forEach { (k, r) -> lines += "precision\t$k\t${r.hits}/${r.total}\t${value(r)}" }
        report.recall.forEach { (k, r) -> lines += "recall\t$k\t${r.hits}/${r.total}\t${value(r)}" }
        report.precisionByRule.forEach { (k, r) -> lines += "precision-by-rule\t$k\t${r.hits}/${r.total}\t${value(r)}" }
        lines += "ambiguous-dm-flagged\t-\t${report.ambiguousDmFlagged.hits}/${report.ambiguousDmFlagged.total}\t${value(report.ambiguousDmFlagged)}"
        lines += "bare-currency-inferred\t-\t${report.bareCurrencyInferred}"
        lines += "wrong-actionable-dates\t-\t${report.wrongActionableDates}"
        lines += "abstention-expected\t-\t${report.abstentionExpected.hits}/${report.abstentionExpected.total}"
        lines += "unexpected-abstentions\t-\t${report.unexpectedAbstentions}"
        lines += "raw-slice-mismatches\t-\t${report.rawSliceMismatches}"
        report.outcomes.forEach { (k, n) -> lines += "outcome\t${k.name}\t$n" }
        report.unitStatuses.forEach { (k, n) -> lines += "unit\t${k.name}\t$n" }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun value(rate: Rate): String = rate.value?.let { "%.4f".format(it) } ?: "no-measurement"
}
