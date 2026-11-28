package org.openlife.app.ui.facts

import org.openlife.vault.facts.FactKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the add-fact form submits (P3-05-R2). Holds the typed text or the picked day in memory only; [toString]
 * names the kind, never the value, so a log or a test message cannot print what the user entered.
 */
sealed interface UserFactInput {
    class Typed(val kind: FactKind, val text: String) : UserFactInput {
        override fun toString(): String = "UserFactInput.Typed(kind=$kind)"
    }

    class Date(val date: LocalDate?) : UserFactInput {
        override fun toString(): String = "UserFactInput.Date"
    }
}

/**
 * P3-05-R2: the day a date picker reports, as a calendar date. The picker's millis are UTC midnight of the chosen
 * day, so the day is read in UTC and never in the device zone (a device zone west of UTC would shift it one day back).
 */
object PickedDate {
    fun fromMillis(millis: Long?): LocalDate? =
        millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
}
