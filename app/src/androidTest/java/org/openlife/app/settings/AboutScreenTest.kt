package org.openlife.app.settings

import android.app.Instrumentation
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.BuildConfig
import org.openlife.app.OpenLifeApp
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme

/**
 * P1-08-R2/R3/R5: About, hosted on the real graph with the production About
 * port, shows this build's version, source revision and signing-certificate
 * fingerprint (computed here independently from PackageManager), a Keystore
 * security label and the licence report, and no activity is started while
 * it is used. Deviation from the step's `Intents.init()`: espresso-intents
 * is not a verified dependency here, so a catch-all blocking
 * [Instrumentation.ActivityMonitor] counts every activity start, including
 * those a Compose URI handler would make.
 *
 * Status: implemented; unverified (gap — pending physical-device run).
 */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val strictMode = StrictModeRule()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<OpenLifeApp>()
    private val container = FakeAppContainer(emptyList(), about = application.container.about)
    private val anyActivity = Instrumentation.ActivityMonitor(null as IntentFilter?, null, true)

    @Before
    fun watchActivityStarts() = instrumentation.addMonitor(anyActivity)

    @After
    fun tearDown() {
        instrumentation.removeMonitor(anyActivity)
        container.close()
    }

    @Test
    fun showsVersionCommitFingerprintAndLicencesWithoutOpeningAnyIntent() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            OpenLifeTheme { OpenLifeNavHost(container, navController) }
        }
        composeRule.runOnIdle { navController.navigate(Routes.About) }

        val gitCommit = BuildConfig::class.java.getField("GIT_COMMIT").get(null) as String
        scrollToDisplayed(hasText(BuildConfig.VERSION_NAME))
        scrollToDisplayed(hasText(BuildConfig.VERSION_CODE.toString()))
        scrollToDisplayed(hasText(gitCommit))
        scrollToDisplayed(hasText(expectedFingerprint()))
        val securityLabels = KeySecurityLevel.entries
            .map { hasText(application.getString(VaultSecurityInfo.label(it))) }
            .reduce { either, other -> either or other }
        scrollToDisplayed(securityLabels)
        scrollToDisplayed(hasText("SQLCipher (AES-256-CBC pages)"))
        scrollToDisplayed(hasText("OpenLife does not open links", substring = true))

        scrollToDisplayed(hasText("Show licences"))
        composeRule.onNodeWithText("Show licences").performClick()
        scrollToDisplayed(hasText("sqlcipher-android", substring = true))
        scrollToDisplayed(hasText("Bundled font licences"))
        // A licence line with an address is plain text: clicking it opens nothing.
        scrollToDisplayed(hasText("https://", substring = true))
        composeRule.onAllNodes(hasText("https://", substring = true))[0].performClick()

        scrollToDisplayed(hasText("Show privacy policy"))
        composeRule.onNodeWithText("Show privacy policy").performClick()
        scrollToDisplayed(hasText("OpenLife privacy policy"))

        composeRule.waitForIdle()
        assertEquals("an activity was started from About", 0, anyActivity.hits)
    }

    private fun scrollToDisplayed(matcher: SemanticsMatcher) {
        val list = composeRule.onNode(hasScrollAction())
        composeRule.waitUntil(TIMEOUT_MS) { runCatching { list.performScrollToNode(matcher) }.isSuccess }
        composeRule.onAllNodes(matcher)[0].assertIsDisplayed()
    }

    @Suppress("DEPRECATION")
    private fun expectedFingerprint(): String {
        val info = application.packageManager.getPackageInfo(
            application.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val certificate = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
