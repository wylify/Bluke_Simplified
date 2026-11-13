package dev.arnv.bluke.update

import kotlin.math.abs

/** How often the app asks GitHub for a new release while it is open. Daily at most, monthly at least. */
enum class UpdateInterval(val days: Int, val label: String) {
    DAILY(1, "Daily"),
    EVERY_3_DAYS(3, "Every 3 days"),
    WEEKLY(7, "Weekly"),
    EVERY_2_WEEKS(14, "Every 2 weeks"),
    MONTHLY(30, "Monthly");

    val millis: Long get() = days * DAY_MILLIS

    companion object {
        val DEFAULT = WEEKLY
        const val MIN_DAYS = 1
        const val MAX_DAYS = 30
        const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** Maps a stored day count to a choice, clamping anything outside daily..monthly. */
        fun fromDays(days: Int): UpdateInterval =
            entries.firstOrNull { it.days == days }
                ?: days.coerceIn(MIN_DAYS, MAX_DAYS).let { clamped -> entries.minBy { abs(it.days - clamped) } }
    }
}
