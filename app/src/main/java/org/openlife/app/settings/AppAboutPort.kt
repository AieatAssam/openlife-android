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
 * vault that is already Ready, read after the app lock allows content reads
 * (review K-08). A failing read is reported by [AboutController] as "could
 * not be read" (review K-02); nothing here logs.
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

    /** The certificates that signed this APK now, not past ones from a rotation. */
    private fun signingCertificates(): List<String> = try {
        signingCertificateBytes().map { SigningFingerprint.sha256(it) }
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    }

    /**
     * API 28+: `signingInfo.apkContentsSigners`, the signers of this APK. API
     * 26-27 have no signing info; their `signatures` array holds the same
     * certificates that signed this APK (no rotation history exists there).
     */
    private fun signingCertificateBytes(): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo(PackageManager.GET_SIGNING_CERTIFICATES)
            return info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        }
        // PackageInfo.signatures is deprecated from API 28; this branch runs only below it.
        @Suppress("DEPRECATION")
        val legacy = packageInfo(PackageManager.GET_SIGNATURES).signatures.orEmpty()
        return legacy.map { it.toByteArray() }
    }

    private fun packageInfo(flags: Int): PackageInfo {
        val manager = app.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(app.packageName, flags)
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

    override suspend fun diagnostics(): DiagnosticsCounts {
        app.appLock.awaitContentAccess()
        return withContext(Dispatchers.IO) {
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
    }

    override suspend fun document(document: BundledDocument): String = withContext(Dispatchers.IO) {
        val resource = when (document) {
            BundledDocument.PRIVACY_POLICY -> R.raw.privacy_policy
            BundledDocument.THIRD_PARTY_LICENCES -> R.raw.third_party_licenses
        }
        app.resources.openRawResource(resource).bufferedReader().use { it.readText() }
    }
}
