package org.openlife.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * P1-20-R5 (AS-5): the non-exported entry accepts a link only with a typed
 * kind, a well-formed id and the current single-use nonce; MainActivity
 * then takes it from memory, never from intent extras.
 */
class InternalLinksTest {
    private val links = InternalLinks()
    private val id = UUID(0L, 7L)

    @Test
    fun aValidLinkWaitsForMainActivityAndIsTakenOnce() {
        assertTrue(links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), links.issueNonce()))

        assertEquals(InternalLink.OpenSource(id), links.pending.value)
        assertEquals(InternalLink.OpenSource(id), links.take())
        assertNull("taken once", links.take())
        assertNull(links.pending.value)
    }

    @Test
    fun anUnknownKindOrMalformedIdIsRejectedWithoutBurningTheNonce() {
        val nonce = links.issueNonce()

        assertFalse("unknown kind", links.accept("open_settings", id.toString(), nonce))
        assertFalse("no kind", links.accept(null, id.toString(), nonce))
        assertFalse("malformed id", links.accept(InternalLinks.KIND_OPEN_SOURCE, "not-a-uuid", nonce))
        assertNull(links.pending.value)
        assertTrue("the nonce is still good", links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), nonce))
    }

    @Test
    fun aMissingGuessedOrReusedNonceIsRejected() {
        assertFalse(links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), null))
        assertFalse(links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), "00".repeat(16)))
        val nonce = links.issueNonce()
        assertTrue(links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), nonce))
        links.take()

        assertFalse("single use", links.accept(InternalLinks.KIND_OPEN_SOURCE, id.toString(), nonce))
        assertNull(links.pending.value)
    }
}
