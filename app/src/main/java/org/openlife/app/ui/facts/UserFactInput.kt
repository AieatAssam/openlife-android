package org.openlife.app.ui.facts

import java.time.LocalDate

/**
 * P3-05-R2: the day a date picker reports, as a calendar date. The picker's millis are UTC midnight of the chosen
 * day, so the day is read in UTC and never in the device zone (a device zone west of UTC would shift it one day back).
 */
object PickedDate {
    fun fromMillis(millis: Long?): LocalDate? = TODO("P3-05-R2 RED stub")
}
