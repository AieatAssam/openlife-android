package org.openlife.app.ui.search

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.MainActivity
import org.openlife.app.R
import org.openlife.app.lock.AppLockPolicyStore
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.FirstRunPreferences

/**
 * Review W-06 (safe on a personally owned tablet): on the real [MainActivity],
 * type a query into search, stop the activity, come back and reopen search:
 * the field is empty. The test restores the first-run flag it sets, in
 * `finally`, and is skipped, not hung, when app lock is on.
 *
 * Split from [SearchScreenTest] (device run 4): a compose rule in the same
 * class stopped the real list from recomposing after its vault state arrived,
 * so this class declares no compose rule. The list is awaited by its loading
 * text, not by sleeping.
 *
 * Status: implemented; unverified (gap - pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class SearchFieldResetTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @Test
    fun fieldIsEmptyAfterTheActivityStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(
            "app lock is on for this install; this test would stop at the lock screen, so it is skipped",
            AppLockPolicyStore.read(context).enabled,
        )
        val wasAcknowledged = FirstRunPreferences.isAcknowledged(context)
        try {
            FirstRunPreferences.setAcknowledged(context)
            typeStopAndReopen(context)
        } finally {
            if (!wasAcknowledged) {
                context.getSharedPreferences(FIRST_RUN_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .remove(FIRST_RUN_ACKNOWLEDGED)
                    .commit()
            }
        }
    }

    private fun typeStopAndReopen(context: Context) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val searchLabel = context.getString(R.string.list_search_content_description)
        val loadingText = context.getString(R.string.list_loading)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc(searchLabel)), WAIT_MS))
            // The list shows a loading placeholder until the vault state arrives; tap search only once it has.
            assertTrue("the list did not finish loading", device.wait(Until.gone(By.text(loadingText)), WAIT_MS))
            device.findObject(By.desc(searchLabel)).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS))
            device.findObject(By.clazz("android.widget.EditText")).text = "synthetic query"
            assertTrue(device.wait(Until.hasObject(By.text("synthetic query")), WAIT_MS))

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            // onStop clears the query (ADR-0010), not the screen: the search field may stay open, or the
            // screen may return to the list with its search button. Either state is accepted; the query is checked below.
            assertTrue(
                "neither the search field nor the search button is shown after resume",
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS) ||
                    device.wait(Until.hasObject(By.desc(searchLabel)), WAIT_MS),
            )
            if (device.hasObject(By.desc(searchLabel))) {
                device.findObject(By.desc(searchLabel)).click()
                assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS))
            }
            assertFalse("the query survived onStop", device.hasObject(By.text("synthetic query")))
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L

        // FirstRunPreferences has no setter that clears the flag; these mirror its private names.
        const val FIRST_RUN_PREFERENCES = "first_run"
        const val FIRST_RUN_ACKNOWLEDGED = "acknowledged"
    }
}
