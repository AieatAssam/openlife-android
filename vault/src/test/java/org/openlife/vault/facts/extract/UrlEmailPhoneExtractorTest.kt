package org.openlife.vault.facts.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0008 §6-§7 (rules-url-v1, rules-email-v1, rules-phone-v1); matrix C2-01, C2-08. */
class UrlEmailPhoneExtractorTest {
    @Test
    fun tableDriven() = FactFixtures.check("url_email_phone.tsv", minimumRows = 70)

    @Test
    fun canonicalBytesKeepTheRawSlice() {
        assertEquals(
            """{"normalized":"https://example.org/a","raw":"HTTPS://Example.org/a"}""",
            only("HTTPS://Example.org/a").valueJson,
        )
        assertEquals("""{"normalized":"Ann@example.org","raw":"Ann@Example.ORG"}""", only("Ann@Example.ORG").valueJson)
        assertEquals(
            """{"normalized":"+441632960123","raw":"+44 1632 960123"}""",
            only("+44 1632 960123").valueJson,
        )
    }

    @Test
    fun abstainedUrlWithUserinfoClaimsItsCharactersSoNoEmailIsRead() {
        val result = FactExtractor.extract(FactFixtures.spansOf("https://bank.example@evil.example"))
        assertEquals(emptyList<ExtractedCandidate>(), result.candidates)
        val abstention = result.abstentions.single()
        assertEquals(ExtractedKind.URL, abstention.kind)
        assertEquals(AbstentionReason.USERINFO, abstention.reason)
        assertEquals(0, abstention.startOffset)
        assertEquals(33, abstention.endOffset)
    }

    /** A URL within the 2,048-character limit whose valueJson exceeds 4,096 bytes is dropped (C2 §7.2). */
    @Test
    fun aValueOverTheJsonCapIsAbstained() {
        val path = "a".repeat(2_020)
        val url = "https://example.org/$path"
        assertTrue(url.length <= 2_048)
        val result = FactExtractor.extract(FactFixtures.spansOf(url))
        assertEquals(emptyList<ExtractedCandidate>(), result.candidates)
        assertEquals(AbstentionReason.LIMIT, result.abstentions.single().reason)
        val shorter = "https://example.org/" + "a".repeat(1_000)
        assertEquals(shorter, only(shorter).rawText)
    }

    @Test
    fun aUrlOverTwoThousandFortyEightCharactersIsAbstained() {
        val url = "https://example.org/" + "a".repeat(2_029)
        assertEquals(2_049, url.length)
        val result = FactExtractor.extract(FactFixtures.spansOf(url))
        assertEquals(emptyList<ExtractedCandidate>(), result.candidates)
        assertEquals(ExtractedKind.URL, result.abstentions.single().kind)
    }

    private fun only(text: String) = FactExtractor.extract(FactFixtures.spansOf(text)).candidates.single()
}
