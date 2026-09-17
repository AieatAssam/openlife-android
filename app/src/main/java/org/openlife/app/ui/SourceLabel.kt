package org.openlife.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.openlife.app.R
import org.openlife.vault.model.Source
import java.text.DateFormat
import java.util.Date

/**
 * Design §8: "The list is ordered by import time and uses generic
 * labels." No filename, no sender-supplied text - only the app's own
 * import timestamp.
 */
@Composable
fun sourceLabel(source: Source): String = stringResource(
    R.string.source_imported,
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(source.importedAt)),
)
