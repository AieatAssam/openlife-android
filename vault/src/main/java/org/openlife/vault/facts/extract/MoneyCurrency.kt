package org.openlife.vault.facts.extract

/** The (currency, rawSymbol) options of an amount, its context evidence, or a marker conflict. */
internal data class CurrencyOptions(
    val currencies: List<Pair<String?, String?>>,
    val rows: List<CurrencyContext> = emptyList(),
    val conflict: Boolean = false,
)

/** ADR-0008 §5.1 marker rules and §5.4 currency context. */
internal object MoneyCurrency {
    private val CONFLICT = CurrencyOptions(emptyList(), conflict = true)

    /** Null when the markers do not make the number money (an English-word code alone without fraction digits). */
    fun resolve(amount: Amount, span: ExtractionSpan, context: CurrencyContextIndex): CurrencyOptions? {
        val symbols = listOfNotNull(amount.prefix?.symbol, amount.suffix?.symbol).distinct()
        val codes = listOfNotNull(amount.prefix?.code, amount.suffix?.code).distinct()
        val symbol = symbols.singleOrNull()
        val code = codes.singleOrNull()
        return when {
            symbols.size > 1 || codes.size > 1 -> CONFLICT
            symbol == null -> codeAlone(code, amount)
            code == null -> symbolAlone(symbol, span, context)
            else -> symbolAndCode(symbol, code)
        }
    }

    private fun codeAlone(code: String?, amount: Amount): CurrencyOptions? = when {
        code == null -> null
        code in MoneyTables.ENGLISH_WORDS && !amount.readings.allFractional -> null
        else -> CurrencyOptions(listOf(code to null))
    }

    private fun symbolAlone(symbol: String, span: ExtractionSpan, context: CurrencyContextIndex): CurrencyOptions {
        val single = MoneyTables.SINGLE[symbol]
        if (single != null) return CurrencyOptions(listOf(single to symbol))
        val (codes, rows) = context.lookup(span, symbol)
        val chosen = if (codes.size in 1..MAX_CONTEXT_CODES) codes else emptyList()
        return CurrencyOptions(listOf<Pair<String?, String?>>(null to symbol) + chosen.map { it to symbol }, rows)
    }

    private fun symbolAndCode(symbol: String, code: String): CurrencyOptions {
        val single = MoneyTables.SINGLE[symbol]
        val accepted = MoneyTables.MULTI[symbol].orEmpty()
        return when {
            single != null -> if (single == code) CurrencyOptions(listOf(code to symbol)) else CONFLICT
            code !in accepted -> CONFLICT
            code in MoneyTables.ENGLISH_WORDS -> CurrencyOptions(listOf(null to symbol, code to symbol))
            else -> CurrencyOptions(listOf(code to symbol))
        }
    }

    private const val MAX_CONTEXT_CODES = 2
}
