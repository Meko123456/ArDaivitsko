package io.github.meko123456.ardaivitsko.time

import android.content.Context
import android.text.format.DateFormat
import io.github.meko123456.ardaivitsko.core.NudgeTexts
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How times and dates are written on screen and in notifications. */
interface TextFormats {
    /** The phone's 12/24-hour setting. */
    fun is24Hour(): Boolean

    fun time(time: LocalTime): String

    /** A short date: "28 Oct", or "Oct 28" in English (US). */
    fun date(date: LocalDate): String

    /** "Thursday, 29 October" */
    fun longDate(date: LocalDate): String

    /** "October 2026" */
    fun month(month: YearMonth): String

    fun texts(zone: ZoneId): NudgeTexts = NudgeTexts(zone, ::time, ::date)
}

/**
 * Follows the phone's 12/24-hour setting and language. Both are read on every call, since the
 * clock setting can change while the app runs without recreating anything.
 */
class AndroidTextFormats(private val context: Context) : TextFormats {

    override fun is24Hour(): Boolean = DateFormat.is24HourFormat(context)

    override fun time(time: LocalTime): String {
        val pattern = if (is24Hour()) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    override fun date(date: LocalDate): String = date.format(best("dMMM", fallback = "d MMM"))

    override fun longDate(date: LocalDate): String = date.format(best("EEEEdMMMM", fallback = "EEEE, d MMMM"))

    override fun month(month: YearMonth): String = month.format(best("MMMMy", fallback = "MMMM yyyy"))

    /** The language's own order for these fields; ICU letters java.time doesn't know fall back. */
    private fun best(skeleton: String, fallback: String): DateTimeFormatter {
        val locale = Locale.getDefault()
        return runCatching { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale) }
            .getOrElse { DateTimeFormatter.ofPattern(fallback, locale) }
    }
}
