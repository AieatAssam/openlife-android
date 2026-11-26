package org.openlife.app.settings

import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.R
import java.nio.file.Files
import java.nio.file.Path

/**
 * P1-08-R3: the wrapping key's security level comes from KeyInfo and is
 * labelled honestly. Secure hardware is claimed only when KeyInfo says so;
 * an unknown level, a failed query or a missing key is never read as either
 * software or hardware.
 */
class VaultSecurityInfoTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun mapsKeyInfoSecurityLevelsToHonestLabels() {
        val api31 = mapOf(
            KeyProperties.SECURITY_LEVEL_STRONGBOX to KeySecurityLevel.STRONGBOX,
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT to KeySecurityLevel.TRUSTED_ENVIRONMENT,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE to KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED,
            KeyProperties.SECURITY_LEVEL_SOFTWARE to KeySecurityLevel.SOFTWARE,
            KeyProperties.SECURITY_LEVEL_UNKNOWN to KeySecurityLevel.NOT_REPORTED,
            UNDEFINED_LEVEL to KeySecurityLevel.NOT_REPORTED,
        )
        api31.forEach { (level, expected) ->
            // On API 31+ the level decides; the deprecated flag cannot upgrade or downgrade it.
            listOf(true, false).forEach { inside ->
                val report = WrappingKeyReport.Reported(level, inside)
                assertEquals("level $level", expected, VaultSecurityInfo.levelOf(report))
            }
        }
        // API 29-30: only isInsideSecureHardware exists, which does not say TEE or StrongBox.
        assertEquals(
            KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED,
            VaultSecurityInfo.levelOf(WrappingKeyReport.Reported(null, true)),
        )
        assertEquals(KeySecurityLevel.SOFTWARE, VaultSecurityInfo.levelOf(WrappingKeyReport.Reported(null, false)))
        assertEquals(KeySecurityLevel.NOT_REPORTED, VaultSecurityInfo.levelOf(WrappingKeyReport.QueryFailed))
        assertEquals(KeySecurityLevel.NO_KEY, VaultSecurityInfo.levelOf(WrappingKeyReport.NoKey))

        val hardware = KeySecurityLevel.entries.filter(VaultSecurityInfo::claimsSecureHardware).toSet()
        assertEquals(
            setOf(
                KeySecurityLevel.STRONGBOX,
                KeySecurityLevel.TRUSTED_ENVIRONMENT,
                KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED,
            ),
            hardware,
        )

        val labels = mapOf(
            KeySecurityLevel.STRONGBOX to R.string.vault_key_level_strongbox,
            KeySecurityLevel.TRUSTED_ENVIRONMENT to R.string.vault_key_level_tee,
            KeySecurityLevel.SECURE_HARDWARE_UNSPECIFIED to R.string.vault_key_level_secure_unspecified,
            KeySecurityLevel.SOFTWARE to R.string.vault_key_level_software,
            KeySecurityLevel.NOT_REPORTED to R.string.vault_key_level_not_reported,
            KeySecurityLevel.NO_KEY to R.string.vault_key_level_no_key,
        )
        KeySecurityLevel.entries.forEach { level ->
            assertEquals(level.name, labels[level], VaultSecurityInfo.label(level))
        }
    }

    /** The copy: no label says "hardware-backed"; only hardware levels mention secure hardware positively. */
    @Test
    fun labelTextNeverOverclaims() {
        val strings = Files.readString(projectDir.resolve("app/src/main/res/values/strings.xml"))
        fun text(name: String): String =
            Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1)
                ?: error("missing string $name")
        assertFalse("no copy may say hardware-backed", strings.contains("hardware-backed", ignoreCase = true))
        listOf("vault_key_level_not_reported", "vault_key_level_no_key").forEach { name ->
            assertFalse("$name mentions hardware", text(name).contains("hardware", ignoreCase = true))
        }
        assertTrue(text("vault_key_level_software").contains("not", ignoreCase = true))
    }

    private companion object {
        const val UNDEFINED_LEVEL = 42
    }
}
