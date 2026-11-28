package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.vault.facts.FactValue
import java.time.LocalDate

/**
 * P3-05 (C2-R38, P3-02 review M-08): a value the user types or chooses goes through the same rules as an extracted
 * one, and an input the rules do not accept is refused with a reason and no value.
 */
class UserValueNormaliserTest {
    private fun accepted(result: UserValueResult): FactValue {
        assertTrue("expected an accepted value, got $result", result is UserValueResult.Accepted)
        return (result as UserValueResult.Accepted).value
    }

    private fun refused(result: UserValueResult): UserValueRefusal {
        assertTrue("expected a refusal, got $result", result is UserValueResult.Refused)
        return (result as UserValueResult.Refused).reason
    }

    @Test
    fun datePickerValueIsCanonicalAndHasNoEvidenceShape() {
        val value = accepted(UserValueNormaliser.date(LocalDate.of(2026, 4, 3)))
        assertEquals("{\"date\":\"2026-04-03\"}", value.valueJson)
        assertEquals("2026-04-03", value.canonicalText)
    }

    @Test
    fun dateOutsideTheSchemaYearRangeIsRefused() {
        assertEquals(UserValueRefusal.NOT_ONE_VALUE, refused(UserValueNormaliser.date(LocalDate.of(999, 1, 1))))
    }

    @Test
    fun timePickerValueIsCanonical() {
        val value = accepted(UserValueNormaliser.time(14, 30, null))
        assertEquals("{\"time\":\"14:30\"}", value.valueJson)
        assertEquals("14:30", value.canonicalText)
    }

    @Test
    fun outOfRangeTimeIsRefused() {
        assertEquals(UserValueRefusal.NOT_ONE_VALUE, refused(UserValueNormaliser.time(25, 0, null)))
    }

    @Test
    fun moneyNeedsAnAmountAndAnIsoCode() {
        val value = accepted(UserValueNormaliser.money("12.50", "GBP"))
        assertEquals("{\"amount\":\"12.50\",\"currency\":\"GBP\",\"rawSymbol\":null}", value.valueJson)
        assertEquals("12.50 GBP", value.canonicalText)
        assertEquals(UserValueRefusal.MONEY_NEEDS_CODE, refused(UserValueNormaliser.money("12.50", null)))
    }

    @Test
    fun moneyAmountThatIsNotDecimalIsRefused() {
        assertEquals(UserValueRefusal.NOT_ONE_VALUE, refused(UserValueNormaliser.money("1e3", "GBP")))
        assertEquals(UserValueRefusal.NOT_ONE_VALUE, refused(UserValueNormaliser.money("0.1.2", "GBP")))
    }

    @Test
    fun urlEmailAndPhoneUseTheExtractorNormalisers() {
        val url = accepted(UserValueNormaliser.text(org.openlife.vault.facts.FactKind.URL, "https://example.org/a"))
        assertTrue(url.valueJson.startsWith("{\"normalized\":\""))
        val email = accepted(UserValueNormaliser.text(org.openlife.vault.facts.FactKind.EMAIL, "name@example.org"))
        assertTrue(email.valueJson.startsWith("{\"normalized\":\""))
        val phone = accepted(UserValueNormaliser.text(org.openlife.vault.facts.FactKind.PHONE, "+44 20 7946 0958"))
        assertTrue(phone.valueJson.startsWith("{\"normalized\":\""))
    }

    @Test
    fun inputTheExtractorRejectsIsRefusedWithoutAValue() {
        val kind = org.openlife.vault.facts.FactKind.URL
        assertEquals(UserValueRefusal.NOT_ONE_VALUE, refused(UserValueNormaliser.text(kind, "not a link at all")))
        assertEquals(
            UserValueRefusal.NOT_ONE_VALUE,
            refused(UserValueNormaliser.text(org.openlife.vault.facts.FactKind.EMAIL, "name@example.org and more")),
        )
    }

    @Test
    fun blankTooLongAndMultilineInputsAreRefused() {
        val url = org.openlife.vault.facts.FactKind.URL
        assertEquals(UserValueRefusal.BLANK, refused(UserValueNormaliser.text(url, "   ")))
        val long = "https://example.org/" + "a".repeat(2100)
        assertEquals(UserValueRefusal.TOO_LONG, refused(UserValueNormaliser.text(url, long)))
        assertEquals(
            UserValueRefusal.CONTROL_CHARACTER,
            refused(UserValueNormaliser.text(url, "https://example.org/a\nhttps://example.org/b")),
        )
    }

    @Test
    fun refusalsNeverPrintTheInput() {
        val reason = refused(UserValueNormaliser.text(org.openlife.vault.facts.FactKind.URL, "secret-text-here"))
        assertTrue(!reason.name.contains("secret"))
    }
}
