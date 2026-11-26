package org.openlife.app.settings

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlife.app.BuildConfig
import org.openlife.app.OpenLifeApp
import org.openlife.app.R
import org.openlife.app.VaultFailureDiagnostics
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * P1-08: the production About port. Reads the build, the package's signing
 * certificate, Keystore's KeyInfo, counts and raw resources, all on IO.
 * Diagnostics never open the vault: item and recovery counts come from a
 * vault that is already Ready.
 */
internal class AppAboutPort(private val app: OpenLifeApp) : AboutPort {
    override suspend fun buildIdentity(): BuildIdentity = withContext(Dispatchers.IO) {
        BuildIdentity(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            gitCommit = BuildConfig.GIT_COMMIT,
            signingCertificatesSha256 = signingCertificates(),
        )
    }

    /** The certificates that signed this APK now (`apkContentsSigners`), not past ones from a rotation. */
    private fun signingCertificates(): List<String> = try {
        packageInfo().signingInfo?.apkContentsSigners.orEmpty().map { SigningFingerprint.sha256(it.toByteArray()) }
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    }

    private fun packageInfo(): PackageInfo {
        val manager = app.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            manager.getPackageInfo(app.packageName, flags)
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    override suspend fun wrappingKey(): WrappingKeyReport = withContext(Dispatchers.IO) {
        try {
            app.keystoreWrapper.wrappingKeySecurity()?.let {
                WrappingKeyReport.Reported(it.securityLevel, it.insideSecureHardware)
            }
                ?: WrappingKeyReport.NoKey
        } catch (_: GeneralSecurityException) {
            WrappingKeyReport.QueryFailed
        } catch (_: ProviderException) {
            WrappingKeyReport.QueryFailed
        }
    }

    override suspend fun diagnostics(): DiagnosticsCounts = withContext(Dispatchers.IO) {
        val ready = app.readyVault.value
        val byState = try {
            ready?.viewRepository?.countSourcesByState()
        } catch (_: SQLiteException) {
            // A reset closed the database between the check and the read; report no item counts.
            null
        } catch (_: IllegalStateException) {
            null
        }
        DiagnosticsCounts(byState, ready?.lastRecovery, VaultFailureDiagnostics.counts(app))
    }

    override suspend fun document(document: BundledDocument): String = withContext(Dispatchers.IO) {
        val resource = when (document) {
            BundledDocument.PRIVACY_POLICY -> R.raw.privacy_policy
            BundledDocument.THIRD_PARTY_LICENCES -> R.raw.third_party_licenses
        }
        app.resources.openRawResource(resource).bufferedReader().use { it.readText() }
    }
}
