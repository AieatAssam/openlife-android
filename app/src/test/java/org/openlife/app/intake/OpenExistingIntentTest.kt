package org.openlife.app.intake

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.UUID
import org.junit.Test

/**
 * F-44: "Open existing" is started from IntakeActivity, which runs in the
 * sharing app's task. Without FLAG_ACTIVITY_NEW_TASK the list opens a second
 * MainActivity inside that foreign task.
 */
class OpenExistingIntentTest {
    @Test
    fun carriesNewTaskAndClearTopFlags() {
        val flags = openExistingIntentFlags()
        assertTrue("FLAG_ACTIVITY_NEW_TASK", flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("FLAG_ACTIVITY_CLEAR_TOP", flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    /**
     * A-14 (P1-19-R17): MainActivity is exported, so any app could send it
     * EXTRA_OPEN_SOURCE_ID. The id is honoured only alongside the current
     * single-use nonce that IntakeActivity's "Open existing" received from
     * this process.
     */
    @Test
    fun openSourceExtraWithoutValidNonceIsIgnored() {
        val nonces = PickerNonce()
        val id = UUID.randomUUID()

        assertNull("no nonce", openSourceRequest(id.toString(), null, nonces))
        assertNull("a guessed nonce", openSourceRequest(id.toString(), "00".repeat(16), nonces))

        val issued = nonces.issue()
        assertNull("a malformed id burns nothing and opens nothing", openSourceRequest("not-a-uuid", issued, nonces))
        assertEquals(id, openSourceRequest(id.toString(), issued, nonces))
        assertNull("the nonce is single-use", openSourceRequest(id.toString(), issued, nonces))
    }
}
