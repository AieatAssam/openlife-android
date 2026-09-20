package org.openlife.vault.repository

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import java.util.Locale

/** Classifies storage exhaustion without exposing provider or filesystem details. */
object IoFailureClassifier {
    fun isStorageUnavailable(error: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is SQLiteFullException ||
                current is ErrnoException && current.errno == OsConstants.ENOSPC
            ) {
                return true
            }
            val message = current.message?.uppercase(Locale.US).orEmpty()
            if ("ENOSPC" in message || "NO SPACE LEFT ON DEVICE" in message) return true
            current = current.cause
        }
        return false
    }
}
