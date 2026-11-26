package org.openlife.vault.facts.extract

/**
 * Reference data of `rules-money-v1` (ADR-0008 §5.3, §5.5). Any change to
 * these lists bumps `rules-money` (C2 Q-13).
 */
internal object MoneyTables {
    private const val LIST_ONE_TEXT =
        "AED AFN ALL AMD AOA ARS AUD AWG AZN BAM BBD BDT BGN BHD BIF BMD BND BOB BOV BRL BSD BTN BWP BYN " +
            "BZD CAD CDF CHE CHF CHW CLF CLP CNY COP COU CRC CUP CVE CZK DJF DKK DOP DZD EGP ERN ETB EUR FJD " +
            "FKP GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HTG HUF IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS " +
            "KHR KMF KPW KRW KWD KYD KZT LAK LBP LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK " +
            "MXN MXV MYR MZN NAD NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD RUB RWF SAR " +
            "SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD TWD TZS UAH " +
            "UGX USD USN UYI UYU UYW UZS VED VES VND VUV WST XAF XAG XAU XBA XBB XBC XBD XCD XCG XDR XOF XPD " +
            "XPF XPT XSU XUA YER ZAR ZMW ZWG"

    private const val DOLLAR_CODES =
        "USD CAD AUD NZD HKD SGD MXN ARS CLP COP TWD JMD TTD BSD BBD BZD BMD BND FJD GYD KYD LRD NAD SBD " +
            "SRD XCD DOP CUP"

    /**
     * ISO 4217 List One, active codes, transcribed by hand on 2026-11-26.
     * The edition (amendment number) of the list transcribed was not
     * recorded, so none is cited. Withdrawn codes (for example EEK, SIT,
     * ESP, STD, HRK, SLL, CUC, ANG, ZWL) are absent. The "no currency" and
     * testing codes XXX and XTS are left out: they never mark an amount.
     *
     * Checked on 2026-11-26 against `java.util.Currency` of the host JDK
     * (OpenJDK 21.0.13.1): every code here is known to it except UYW,
     * which that JDK lacks; of its codes, only withdrawn ones and XAD are
     * absent here. BGN is kept (that JDK lists it); whether List One still
     * lists it after Bulgaria's euro changeover is open question 21.
     */
    val LIST_ONE: Set<String> = LIST_ONE_TEXT.split(' ').toSet()

    /** §5.5 normative list: never context; beside digits still a marker. */
    val ENGLISH_NORMATIVE: Set<String> = setOf("CAD", "COP", "CUP", "GIP", "NAD", "SHP")

    /** §5.5 normative and informative lists: alone, a marker only with printed fraction digits. */
    val ENGLISH_WORDS: Set<String> = ENGLISH_NORMATIVE + setOf(
        "ALL", "BAM", "BOB", "GEL", "MAD", "MOP", "PEN", "RUB", "SOS", "TOP", "TRY", "AMD", "PHP", "RON",
    )

    /** §5.3 single-code symbols, longest first where one is a suffix of another. */
    val SINGLE: Map<String, String> = linkedMapOf(
        "US$" to "USD", "CA$" to "CAD", "AU$" to "AUD", "NZ$" to "NZD", "HK$" to "HKD", "S$" to "SGD", "R$" to "BRL",
        "€" to "EUR", "₹" to "INR", "₺" to "TRY", "₽" to "RUB", "₪" to "ILS", "₴" to "UAH", "₫" to "VND",
        "₦" to "NGN", "₸" to "KZT", "₼" to "AZN", "₾" to "GEL",
    )

    /** §5.3 multi-code symbols. */
    val MULTI: Map<String, List<String>> = mapOf(
        "$" to DOLLAR_CODES.split(' '),
        "£" to listOf("GBP", "EGP", "FKP", "GIP", "LBP", "SHP", "SSP", "SYP"),
        "¥" to listOf("JPY", "CNY"),
        "₩" to listOf("KRW", "KPW"),
        "kr" to listOf("DKK", "ISK", "NOK", "SEK"),
    )

    /** Every code any multi-code symbol can take: the only codes that can ever be context (§5.5). */
    val CONTEXT_CODES: Set<String> = MULTI.values.flatten().toSet() - ENGLISH_NORMATIVE
}

/**
 * Currency pairs (ruling 2026-11-26): a code joined by `/`, with at most one
 * space separator each side, to another List One code (`EUR/USD`) names an
 * exchange rate, not an amount's currency. Neither code is a marker or
 * context. Reads the text as printed; constant window.
 */
internal object CurrencyPairs {
    private const val CODE = 3

    /** The end of a pair whose first code ends at [end], or null. */
    fun endAfter(text: String, end: Int): Int? {
        var at = end
        if (at < text.length && LineScan.isSpace(text[at])) at++
        if (text.getOrNull(at) != '/') return null
        at++
        if (at < text.length && LineScan.isSpace(text[at])) at++
        return (at + CODE).takeIf { isCode(text, at) && !letterAt(text, at + CODE) }
    }

    /** True when the code at `[start, start + 3)` is the second code of a pair. */
    fun isSecond(text: String, start: Int): Boolean {
        var at = start - 1
        if (at >= 0 && LineScan.isSpace(text[at])) at--
        if (text.getOrNull(at) != '/') return false
        at--
        if (at >= 0 && LineScan.isSpace(text[at])) at--
        val first = at + 1 - CODE
        return first >= 0 && isCode(text, first) && (first == 0 || !Character.isLetter(text.codePointBefore(first)))
    }

    private fun isCode(text: String, at: Int): Boolean =
        at >= 0 && at + CODE <= text.length && text.substring(at, at + CODE) in MoneyTables.LIST_ONE

    private fun letterAt(text: String, at: Int): Boolean = at < text.length && Character.isLetter(text.codePointAt(at))
}
