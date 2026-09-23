package org.openlife.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
fun sourceLabel(source: Source): String = stringResource(R.string.source_imported, importedTime(source))

/** The app's own import timestamp; data, so it is set in the data face where shown. */
fun importedTime(source: Source): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(source.importedAt))

/** [text] with every occurrence of [data] set in the data (Plex Mono) face. */
@Composable
fun withDataSpan(text: String, data: String): AnnotatedString {
    val dataStyle = SpanStyle(fontFamily = MaterialTheme.typography.labelMedium.fontFamily)
    return buildAnnotatedString {
        append(text)
        var start = if (data.isEmpty()) -1 else text.indexOf(data)
        while (start >= 0) {
            addStyle(dataStyle, start, start + data.length)
            start = text.indexOf(data, start + data.length)
        }
    }
}
