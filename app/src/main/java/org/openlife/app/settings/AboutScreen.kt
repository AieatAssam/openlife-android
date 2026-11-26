package org.openlife.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.openlife.app.R
import org.openlife.app.ui.BackTopAppBar
import org.openlife.app.ui.brand.PerforationDivider
import org.openlife.app.ui.theme.OpenLifeMono

/**
 * P1-08: About. One lazy list, so the licence report (tens of kilobytes,
 * read on IO and split into lines) scrolls without measuring all of it.
 * Addresses are plain text: nothing on this screen is a link (P1-08-R5).
 * Data (version, revision, fingerprint, counts) is set in Plex Mono.
 */
@Composable
fun AboutScreen(
    state: AboutState = AboutState(),
    onToggleDocument: (BundledDocument) -> Unit = {},
    onBack: () -> Unit = {},
    lowRamDevice: Boolean = false,
) {
    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = { BackTopAppBar(title = stringResource(R.string.about_title), onBack = onBack) },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { AboutHeader(lowRamDevice) }
                section(R.string.about_section_build) { BuildSection(state.identity) }
                section(R.string.about_section_security) { SecuritySection(state.keyLevel) }
                section(R.string.about_section_will_not_do) { WillNotDoSection() }
                section(R.string.about_section_diagnostics) { DiagnosticsSection(state.diagnostics) }
                documentSection(BundledDocument.PRIVACY_POLICY, state.documents, onToggleDocument)
                documentSection(BundledDocument.THIRD_PARTY_LICENCES, state.documents, onToggleDocument)
            }
        }
    }
}

@Composable
private fun AboutHeader(lowRamDevice: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The wordmark: Fraunces through the display role (VISUAL_IDENTITY §2 allows it on About).
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.about_subtitle), style = MaterialTheme.typography.bodyLarge)
        if (lowRamDevice) {
            // P1-16-R4: the supported RAM floor is 2 GB; say so where it applies.
            Text(stringResource(R.string.about_low_ram_warning), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun LazyListScope.section(title: Int, content: @Composable () -> Unit) {
    item { PerforationDivider() }
    item { SectionHeading(title) }
    item { content() }
}

@Composable
internal fun SectionHeading(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun BuildSection(identity: BuildIdentity?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (identity == null) {
            Text(stringResource(R.string.about_document_loading))
            return@Column
        }
        Fact(R.string.about_version_name, identity.versionName, data = true)
        Fact(R.string.about_version_code, identity.versionCode.toString(), data = true)
        Fact(R.string.about_git_commit, identity.gitCommit, data = true)
        if (identity.signingCertificatesSha256.isEmpty()) {
            Fact(R.string.about_signing_certificate, stringResource(R.string.about_signing_unavailable))
        } else {
            identity.signingCertificatesSha256.forEach { Fact(R.string.about_signing_certificate, it, data = true) }
        }
        Text(stringResource(R.string.about_signing_explanation), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SecuritySection(keyLevel: KeySecurityLevel?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Fact(
            R.string.about_security_key,
            stringResource(keyLevel?.let(VaultSecurityInfo::label) ?: R.string.about_document_loading),
        )
        Fact(R.string.about_security_database, stringResource(R.string.about_security_database_value))
        Fact(R.string.about_security_files, stringResource(R.string.about_security_files_value))
        Fact(R.string.about_security_backup, stringResource(R.string.about_security_backup_value))
        Text(stringResource(R.string.about_security_boundary), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WillNotDoSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WILL_NOT_DO.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A small ink mark, decorative; the sentence carries the meaning.
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Text(stringResource(item), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.about_will_not_reason), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A labelled fact; [data] values are set in Plex Mono (VISUAL_IDENTITY §5). */
@Composable
private fun Fact(label: Int, value: String, data: Boolean = false) {
    Column {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = if (data) dataStyle() else MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun dataStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = OpenLifeMono)

private val WILL_NOT_DO = listOf(
    R.string.about_will_not_export,
    R.string.about_will_not_share,
    R.string.about_will_not_sync,
    R.string.about_will_not_cloud_backup,
    R.string.about_will_not_transfer,
)
