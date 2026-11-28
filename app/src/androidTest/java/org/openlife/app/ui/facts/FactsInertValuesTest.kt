package org.openlife.app.ui.facts

import android.app.Instrumentation
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.R
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.FakeFactsGateway
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.facts.FactKind
import org.openlife.vault.facts.FactOrigin
import org.openlife.vault.facts.FactStatus
import org.openlife.vault.facts.FactValue
import org.openlife.vault.model.ImageFormat
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.model.Orientation
import org.openlife.vault.model.Source
import org.openlife.vault.model.SourceState

/**
 * P3-05-R3 (C2 inert values): a URL, email or phone fact is plain text with the note "OpenLife does not open links or
 * copy them". Tapping or long-pressing the value offers no action, and no activity starts (the activity monitor
 * stands in for espresso-intents, which is not a verified dependency here, see AboutScreenTest).
 *
 * Not proven here: the absence of a text-selection container. Selection is absent from the source (no
 * SelectionContainer, ClickableText or LinkAnnotation in ui/facts); that is a source check recorded in the step evidence.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class FactsInertValuesTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private var monitor: Instrumentation.ActivityMonitor? = null

    @After
    fun removeMonitor() {
        monitor?.let { InstrumentationRegistry.getInstrumentation().removeMonitor(it) }
    }

    @Test
    fun urlAndPhoneValuesAreInertPlainText() {
        val source = source(UUID(0L, 51L))
        val url = "https://example.org/notice"
        val phone = "+442079460000"
        val rows = listOf(
            FakeFactsGateway.row(
                source.id,
                FactKind.URL,
                FactOrigin.EXTRACTED,
                FactStatus.UNREVIEWED,
                FactValue("{\"url\":\"$url\"}", url),
            ),
            FakeFactsGateway.row(
                source.id,
                FactKind.PHONE,
                FactOrigin.EXTRACTED,
                FactStatus.UNREVIEWED,
                FactValue("{\"phone\":\"$phone\"}", phone),
            ),
        )
        setHost(FakeAppContainer(listOf(source), factsGateway = FakeFactsGateway(rows = rows, readingId = null)))
        composeRule.runOnIdle { navController.navigate(Routes.Facts(source.id.toString())) }
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty() }

        val note = context.getString(R.string.facts_inert_note)
        composeRule.onAllNodesWithText(note)[0].assertIsDisplayed()
        composeRule.onNodeWithText(url).assertHasNoClickAction()
        composeRule.onNodeWithText(phone).assertHasNoClickAction()

        val started = Instrumentation.ActivityMonitor(null as IntentFilter?, null, true)
        monitor = started
        InstrumentationRegistry.getInstrumentation().addMonitor(started)
        composeRule.onNodeWithText(url).performClick()
        composeRule.onNodeWithText(url).performTouchInput { longClick() }
        composeRule.onNodeWithText(phone).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals("tapping or long-pressing an inert value must start no activity", 0, started.hits)
    }

    private fun setHost(fake: FakeAppContainer) {
        composeRule.setContent {
            navController = rememberNavController()
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 800.dp))) {
                OpenLifeTheme { OpenLifeNavHost(fake, navController) }
            }
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source(id: UUID) = Source(
        id = id,
        state = SourceState.READY,
        importedAt = 1L,
        intakeKind = IntakeKind.SHARE,
        mimeType = ImageFormat.JPEG,
        byteCount = 10,
        sha256 = ByteArray(32),
        width = 64,
        height = 48,
        orientation = Orientation.NORMAL,
        wrappedDek = ByteArray(16),
        artefactVersion = 1,
    )

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
