package org.openlife.app.intake

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openlife.app.OpenLifeApp
import org.openlife.app.VaultAccess
import org.openlife.vault.model.IntakeKind
import org.openlife.vault.repository.ImportLimits
import org.openlife.vault.repository.PrepareResult

/**
 * The single exported intake activity (design §8/§11). Every incoming
 * intent is validated here regardless of the declared `<intent-filter>`,
 * because an exported activity can be started directly with an arbitrary
 * intent (design §8: "Validate every incoming intent regardless of the
 * filter"). This screen is a Stage-6 placeholder for the real preview
 * screen design §8's user flow describes ("Preview shows a sampled
 * rendering...") — that UI is Stage 7. What this activity is responsible
 * for now: rejecting a hostile or malformed intent, and handing a
 * validated, already-opened, bounded stream to the vault repository
 * without ever letting a `Uri`, filename, or claimed sender identity reach
 * it (design §7 repository layout).
 */
class IntakeActivity : ComponentActivity() {

    private var statusText by mutableStateOf("Preparing…")

    /** Test-only observation seam; Stage 7's real UI replaces this screen entirely. */
    fun currentStatusForTest(): String = statusText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(statusText)
                    }
                }
            }
        }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val shape = extractShape(intent)
        when (val validation = IntakeIntentValidator.validate(shape, packageName)) {
            is IntakeValidationResult.Rejected -> {
                statusText = "Not imported: ${validation.reason}"
            }

            is IntakeValidationResult.Valid -> {
                proceedWithUri(Uri.parse(validation.uriString))
            }
        }
    }

    private fun extractShape(intent: Intent): IntentShape {
        val extraStreamUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val clipDataUris = buildList {
            val clipData: ClipData? = intent.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { add(it.toString()) }
                }
            }
        }
        return IntentShape(
            action = intent.action,
            dataUri = intent.data?.toString(),
            extraStreamUri = extraStreamUri?.toString(),
            clipDataUris = clipDataUris,
        )
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtraCompat(intent: Intent, name: String, type: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, type)
        } else {
            intent.getParcelableExtra(name)
        }

    private fun proceedWithUri(uri: Uri) {
        // The URI itself is never handed to the vault layer - only the
        // bytes it names and the provider-reported MIME type (design §7:
        // "No UI composable may accept a persistent content URI as the
        // saved Source" - the same boundary applies here, one layer below
        // any composable).
        lifecycleScope.launch {
            val access = (application as OpenLifeApp).vault()
            when (access) {
                is VaultAccess.Unavailable -> statusText = "Vault unavailable: ${access.reason}"
                is VaultAccess.Ready -> importFrom(uri, access)
            }
        }
    }

    private suspend fun importFrom(uri: Uri, access: VaultAccess.Ready) {
        val declaredType = contentResolver.getType(uri)
        val stream = try {
            contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            // Missing or expired URI grant (design §11 step 1: "If a share
            // grant expires or a provider disappears, ask the user to
            // select the item again").
            statusText = "Could not access the selected item; please select it again."
            return
        } catch (e: java.io.FileNotFoundException) {
            // The provider itself failed or the item is gone - same
            // reselect path, not a crash (design §9: "unavailable
            // provider" is a non-sensitive error category).
            statusText = "Could not access the selected item; please select it again."
            return
        }
        if (stream == null) {
            statusText = "Could not access the selected item; please select it again."
            return
        }

        val result = stream.use { input ->
            withContext(Dispatchers.IO) {
                // Cooperative cancellation: closing the descriptor on a
                // provider that never delivers bytes is what unblocks the
                // otherwise-synchronous read inside prepareImport, which
                // then fails closed as a normal I/O error (design §12: "not
                // a guaranteed hard stop for a blocked provider").
                val deadline = launch {
                    delay(ImportLimits.PROVIDER_READ_DEADLINE_SECONDS * 1000)
                    input.close()
                }
                try {
                    access.importRepository.prepareImport(input, declaredType ?: "", IntakeKind.SHARE)
                } finally {
                    deadline.cancel()
                }
            }
        }

        statusText = when (result) {
            is PrepareResult.Prepared -> "Prepared ${result.format} ${result.width}x${result.height}"
            is PrepareResult.Rejected -> "Not imported: ${result.reason}"
            PrepareResult.Busy -> "Another import is already in progress."
            PrepareResult.Failed -> "Import failed; please try again."
        }
    }
}
