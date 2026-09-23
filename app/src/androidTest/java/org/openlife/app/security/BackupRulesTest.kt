package org.openlife.app.security

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlife.app.test.StrictModeRule
import org.openlife.app.R

/**
 * Verifies that the privacy boundary is present in the packaged application,
 * not only in source XML that could drift from the merged manifest.
 *
 * This is an emulator-observable backup configuration check. It does not claim
 * to reproduce manufacturer-specific cloud or device-transfer behaviour.
 */
@RunWith(AndroidJUnit4::class)
class BackupRulesTest {
    @get:Rule
    val strictMode = StrictModeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedManifestAndRulesExcludeAppDataFromBackupAndTransfer() {
        val packageManager = context.packageManager
        val appInfo = packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        val permissions = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE))

        val modernRules = readTags(R.xml.data_extraction_rules)
        assertTrue(modernRules.any { it.name == "cloud-backup" })
        assertTrue(modernRules.any { it.name == "device-transfer" })
        listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_file",
            "device_database",
            "device_sharedpref",
        ).forEach { domain ->
            assertTrue(
                "cloud/device rules must exclude $domain",
                modernRules.count { it.name == "exclude" && it.domain == domain } >= 2,
            )
        }

        val legacyRules = readTags(R.xml.backup_rules)
        listOf("root", "file", "database", "sharedpref", "external").forEach { domain ->
            assertTrue(
                "legacy rules must exclude $domain",
                legacyRules.any { it.name == "exclude" && it.domain == domain },
            )
        }
    }

    private fun readTags(resourceId: Int): List<RuleTag> {
        val parser = context.resources.getXml(resourceId)
        return parser.use { xml ->
            buildList {
                while (xml.next() != XmlResourceParser.END_DOCUMENT) {
                    if (xml.eventType == XmlResourceParser.START_TAG) {
                        add(RuleTag(xml.name, xml.getAttributeValue(null, "domain")))
                    }
                }
            }
        }
    }

    private data class RuleTag(val name: String, val domain: String?)
}
