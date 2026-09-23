package org.openlife.app.intake

import android.content.Intent
import org.junit.Assert.assertTrue
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
}
