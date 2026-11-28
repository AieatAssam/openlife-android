package org.openlife.vault.facts.extract

import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactLimits
import org.openlife.vault.facts.FactValue
import java.time.LocalDate

/**
 * Why a user-entered value was refused. The reason names no input text (C2-R50).
 */
enum class UserValueRefusal {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,

    /** A money amount needs an ISO 4217 code: ADR-0008 stores "not stated" only with a printed symbol (P3-05 notes). */
    MONEY_NEEDS_CODE,

    /** The rules do not accept exactly one value of the kind covering the whole input. */
    NOT_ONE_VALUE,
}

/** A user-entered value: accepted with its stored form, or refused with a reason and no value (C2-R38). */
sealed interface UserValueResult {
    data class Accepted(val value: FactValue) : UserValueResult

    data class Refused(val reason: UserValueRefusal) : UserValueResult
}

/**
 * P3-05 (C2-R38, P3-02 review M-08): the single-value entry point for a value
 * the user types or chooses. Typed text goes through the same scanners as an
 * extracted value (P3-03), and the input must produce exactly one value of the
 * kind that covers the whole input, not clarification-needed. Pickers
 * (date, time) construct the typed value directly, then use the same
 * canonical writer. Nothing is corrected silently: a refused input is
 * refused, and an accepted input is stored in its canonical form.
 */
object UserValueNormaliser {
    private const val USER_SPAN_ID = "user-input"

    /** The ISO 4217 codes the money chooser offers (P3-05 R2). Each must accept a plain amount (see the host test). */
    val CURRENCY_CHOICES: List<String> = listOf("CHF", "DKK", "EUR", "GBP", "INR", "JPY", "NOK", "PLN", "SEK", "USD")
    private const val CONTROL_LIMIT = 0x20
    private const val DELETE = 0x7F

    /** A date from a date picker (C2-R38). */
    fun date(date: LocalDate): UserValueResult = typed { DateValue(date) }

    /** A time from a time picker (C2-R38); [second] is null when the picker has none. */
    fun time(hour: Int, minute: Int, second: Int?): UserValueResult = typed { TimeValue(hour, minute, second) }

    /** An amount with an ISO 4217 code chosen from the list (C2-R38). */
    fun money(amount: String, currencyCode: String?): UserValueResult {
        if (amount.isBlank()) return UserValueResult.Refused(UserValueRefusal.BLANK)
        if (currencyCode == null) return UserValueResult.Refused(UserValueRefusal.MONEY_NEEDS_CODE)
        return extracted(ExtractedKind.MONEY, amount.trim() + " " + currencyCode)
    }

    /** URL, email address or phone number typed as text (C2-R38). Any other kind is refused. */
    fun text(kind: FactKind, input: String): UserValueResult {
        val extractedKind = when (kind) {
            FactKind.URL -> ExtractedKind.URL
            FactKind.EMAIL -> ExtractedKind.EMAIL
            FactKind.PHONE -> ExtractedKind.PHONE
            else -> return UserValueResult.Refused(UserValueRefusal.NOT_ONE_VALUE)
        }
        return extracted(extractedKind, input.trim())
    }

    private fun extracted(kind: ExtractedKind, text: String): UserValueResult {
        inputRefusal(text)?.let { return UserValueResult.Refused(it) }
        val candidates = FactExtractor.extract(listOf(ExtractionSpan(USER_SPAN_ID, null, 0, text))).candidates
        val only = candidates.singleOrNull()
        val value = only?.takeIf {
            it.kind == kind && it.startOffset == 0 && it.endOffset == text.length && !it.needsClarification
        }?.canonicalValue
        return if (value == null) UserValueResult.Refused(UserValueRefusal.NOT_ONE_VALUE) else accepted(value)
    }

    private fun inputRefusal(text: String): UserValueRefusal? = when {
        text.isBlank() -> UserValueRefusal.BLANK
        text.length > FactLimits.MAX_USER_TEXT_CHARS -> UserValueRefusal.TOO_LONG
        text.any { it.code < CONTROL_LIMIT || it.code == DELETE } -> UserValueRefusal.CONTROL_CHARACTER
        CanonicalJson.hasUnpairedSurrogate(text) -> UserValueRefusal.NOT_ONE_VALUE
        else -> null
    }

    private inline fun typed(build: () -> TypedValue): UserValueResult = try {
        accepted(build())
    } catch (_: IllegalArgumentException) {
        UserValueResult.Refused(UserValueRefusal.NOT_ONE_VALUE)
    }

    private fun accepted(value: TypedValue): UserValueResult.Accepted =
        UserValueResult.Accepted(FactValue(CanonicalJson.of(value, emptyList()), value.canonicalText()))
}
