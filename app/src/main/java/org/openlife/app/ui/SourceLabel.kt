package org.openlife.app.ui

import org.openlife.vault.model.Source
import java.text.DateFormat
import java.util.Date

/**
 * Design §8: "The list is ordered by import time and uses generic
 * labels." No filename, no sender-supplied text - only the app's own
 * import timestamp.
 */
fun sourceLabel(source: Source): String =
    "Imported ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(source.importedAt))}"
