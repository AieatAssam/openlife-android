package org.openlife.app.settings

import android.app.Instrumentation
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
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
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.BuildConfig
import org.openlife.app.OpenLifeApp
import org.openlife.app.R
import org.openlife.app.navigation.OpenLifeNavHost
import org.openlife.app.navigation.Routes
import org.openlife.app.test.FakeAppContainer
import org.openlife.app.test.StrictModeRule
import org.openlife.app.ui.theme.OpenLifeTheme
import org.openlife.vault.crypto.KeystoreWrapper

/**
 * P1-08-R2/R3/R5: About, hosted on the real graph with the production About
 * port, shows this build's version, source revision and signing-certificate
 * fingerprint (computed here independently from PackageManager), the
 * Keystore security label (computed here independently from KeyInfo) and
 * the licence report, and no activity is started while it is used. The
 * port is built directly (review K-04): the app container is never touched,
 * so the test opens no vault and creates no key. Deviation from the step's `Intents.init()`: espresso-intents
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
    private val container = FakeAppContainer(emptyList(), about = AppAboutPort(application))
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
        val versionCode = BuildConfig.VERSION_CODE.toString()
        scrollToDisplayed(hasText(BuildConfig.VERSION_NAME))
        // Review K-05: the version code next to its own label, not any count that happens to match.
        scrollToDisplayed(hasText(application.getString(R.string.about_version_code)) and hasText(versionCode))
        scrollToDisplayed(hasText(gitCommit))
        scrollToDisplayed(hasText(expectedFingerprint()))
        scrollToDisplayed(hasText(application.getString(R.string.about_security_key)) and hasText(expectedKeyLabel()))
        scrollToDisplayed(hasText("SQLCipher (AES-256-CBC pages)"))
        scrollToDisplayed(hasText("OpenLife does not open links", substring = true))

        scrollToDisplayed(hasText("Show licences"))
        composeRule.onNodeWithText("Show licences").performClick()
        scrollToDisplayed(hasText("sqlcipher-android", substring = true))
        scrollToDisplayed(hasText("Bundled font licences"))
        // An address in the licences is plain text: its node has no click action, and a tap on it
        // starts no activity (the monitor below). This does not prove anything about other text.
        val address = hasText("https://", substring = true)
        scrollToDisplayed(address)
        composeRule.onAllNodes(address)[0].assert(hasClickAction().not()).performClick()

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
        // Mirrors AppAboutPort.signingCertificateBytes: signingInfo exists from API 28 only.
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ).signingInfo!!.apkContentsSigners.first().toByteArray()
        } else {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.GET_SIGNATURES,
            ).signatures!!.first().toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }
    }

    /** The key label from KeyInfo, read here without the app's code (null key: none in Keystore). */
    private fun expectedKeyLabel(): String {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = store.getKey(KeystoreWrapper.DEFAULT_ALIAS, null) as? SecretKey
            ?: return application.getString(R.string.vault_key_level_no_key)
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> R.string.vault_key_level_strongbox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> R.string.vault_key_level_tee
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> R.string.vault_key_level_secure_unspecified
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> R.string.vault_key_level_software
                else -> R.string.vault_key_level_not_reported
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) R.string.vault_key_level_secure_unspecified else R.string.vault_key_level_software
        }
        return application.getString(label)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TIMEOUT_MS = 10_000L
    }
}
