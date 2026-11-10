package io.github.meko123456.ardaivitsko.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A task as one spoken or typed sentence describes it: "renew the parking permit on Thursday at 10". */
data class SpokenTask(
    val title: String,
    val day: LocalDate,
    /** The deadline the sentence named, or null for the day's end. */
    val time: LocalTime?,
    val repeat: Repeat,
)

/**
 * Turns a sentence into a task, with rules rather than a model, so it works offline and the same
 * way every time. English only, as the app is. What it understands:
 *
 * - **Repeats:** "every day", "daily", "every week", "weekly", "every month", "monthly", "every
 *   Monday" (weekly, from the next Monday), "every morning / evening / night" (daily, at 9:00,
 *   18:00 or 21:00).
 * - **Days:** "today", "tonight", "tomorrow", "the day after tomorrow", a weekday ("on Thursday",
 *   "this Thursday": the next one, today included; "next Thursday": after today), "next week" (a
 *   week from today), "in 3 days / two weeks / a month", a date ("15 November", "November 15th",
 *   "on the 15th", "15.11", "15/11"): the next such day, today included.
 * - **Times:** "at 10", "at 10:30", "at 5 pm", "at ten", "10 o'clock", "noon", "midnight",
 *   "in the morning" (9:00), "in the afternoon" (15:00), "in the evening" (18:00), "tonight"
 *   (20:00). An hour with no am or pm from 1 to 6 is taken as afternoon, 7 to 12 as the morning
 *   and noon.
 * - **The title** is what's left, without "remind me to", "add a task", "don't forget to" and the
 *   like at the start, with its first letter capitalized. A sentence that was all date and time
 *   leaves the title empty.
 *
 * Nothing understood leaves the task on [today] with no time.
 */
object SpokenTasks {

    fun parse(text: String, today: LocalDate): SpokenTask {
        var working = text
        var repeat = Repeat.NEVER
        var day: LocalDate? = null
        var time: LocalTime? = null
        /** A time implied by a word ("tonight"), used only when no time is said outright. */
        var impliedTime: LocalTime? = null

        fun take(regex: Regex, onMatch: (MatchResult) -> Unit) {
            val match = regex.find(working) ?: return
            onMatch(match)
            working = working.blank(match.range)
        }

        // Repeats first: "every Monday" names a weekday that the weekday rule must not read twice.
        take(EVERY_WEEKDAY) { m ->
            repeat = Repeat.WEEKLY
            day = today.with(TemporalAdjusters.nextOrSame(weekday(m.groupValues[1])))
        }
        take(EVERY_PART_OF_DAY) { m ->
            repeat = Repeat.DAILY
            impliedTime = partOfDay(m.groupValues[1])
        }
        take(DAILY) { repeat = Repeat.DAILY }
        take(WEEKLY) { repeat = Repeat.WEEKLY }
        take(MONTHLY) { repeat = Repeat.MONTHLY }

        // Times before days: "at 10" must go before a bare "10" could be read as a day of the month.
        take(TIME_AT) { m -> time = clockTime(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        if (time == null) take(TIME_AM_PM) { m -> time = clockTime(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        if (time == null) take(TIME_OCLOCK) { m -> time = clockTime(m.groupValues[1], "", "") }
        if (time == null) take(NOON) { time = LocalTime.NOON }
        if (time == null) take(MIDNIGHT) { time = LocalTime.of(23, 59) }
        if (time == null) take(PART_OF_DAY) { m -> time = partOfDay(m.groupValues[1]) }

        // Days, the most specific first; every phrase understood leaves the title either way.
        take(DAY_MONTH) { m -> day = dateOn(m.groupValues[1].toInt(), month(m.groupValues[2]), today) }
        take(MONTH_DAY) { m -> if (day == null) day = dateOn(m.groupValues[2].toInt(), month(m.groupValues[1]), today) }
        take(NUMERIC_DATE) { m -> if (day == null) day = dateOn(m.groupValues[1].toInt(), m.groupValues[2].toInt().let { Month.of(it.coerceIn(1, 12)) }, today) }
        take(DAY_OF_MONTH) { m -> if (day == null) day = nextDayOfMonth(m.groupValues[1].toInt(), today) }
        take(IN_SOME_TIME) { m ->
            if (day == null) {
                val count = number(m.groupValues[1])
                day = when (m.groupValues[2].lowercase(Locale.ENGLISH).trimEnd('s')) {
                    "day" -> today.plusDays(count)
                    "week" -> today.plusWeeks(count)
                    else -> today.plusMonths(count)
                }
            }
        }
        take(NEXT_WEEKDAY) { m -> if (day == null) day = today.with(TemporalAdjusters.next(weekday(m.groupValues[1]))) }
        take(WEEKDAY) { m -> if (day == null) day = today.with(TemporalAdjusters.nextOrSame(weekday(m.groupValues[1]))) }
        take(NEXT_WEEK) { if (day == null) day = today.plusWeeks(1) }
        take(DAY_AFTER_TOMORROW) { if (day == null) day = today.plusDays(2) }
        take(TOMORROW) { if (day == null) day = today.plusDays(1) }
        take(TONIGHT) {
            if (day == null) day = today
            if (impliedTime == null) impliedTime = LocalTime.of(20, 0)
        }
        take(TODAY) { if (day == null) day = today }

        // A monthly task with no day named comes on the day the sentence was said.
        return SpokenTask(
            title = title(working),
            day = day ?: today,
            time = time ?: impliedTime,
            repeat = repeat,
        )
    }

    private fun title(working: String): String {
        var title = working.replace(WHITESPACE, " ").trim().trim(',', '.', ';', ':', '-').trim()
        title = title.replace(LEADING_FILLER, "")
        title = title.replace(DANGLING_WORD_AT_END, "").trim()
        title = title.replace(WHITESPACE, " ").trim().trim(',', '.', ';', ':', '-').trim()
        return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
    }

    private fun String.blank(range: IntRange): String =
        substring(0, range.first) + " ".repeat(range.last - range.first + 1) + substring(range.last + 1)

    private fun weekday(word: String): DayOfWeek {
        val key = word.lowercase(Locale.ENGLISH).take(3)
        return DayOfWeek.entries.first { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ENGLISH).take(3) == key }
    }

    private fun month(word: String): Month {
        val key = word.lowercase(Locale.ENGLISH).take(3)
        return Month.entries.first { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(Locale.ENGLISH).take(3) == key }
    }

    /** The next [day] of [month] from [today] on, this year or next; a day the month lacks clamps to its last. */
    private fun dateOn(day: Int, month: Month, today: LocalDate): LocalDate {
        fun inYear(year: Int): LocalDate {
            val length = month.length(java.time.Year.isLeap(year.toLong()))
            return LocalDate.of(year, month, day.coerceIn(1, length))
        }
        val thisYear = inYear(today.year)
        return if (thisYear >= today) thisYear else inYear(today.year + 1)
    }

    /** "On the 15th": the next 15th from [today] on. */
    private fun nextDayOfMonth(day: Int, today: LocalDate): LocalDate {
        fun inMonth(month: LocalDate) = month.withDayOfMonth(day.coerceIn(1, month.lengthOfMonth()))
        val thisMonth = inMonth(today)
        return if (thisMonth >= today) thisMonth else inMonth(today.plusMonths(1))
    }

    private fun clockTime(hourText: String, minuteText: String, amPm: String): LocalTime? {
        val hour = number(hourText).toInt()
        val minute = minuteText.toIntOrNull() ?: 0
        if (hour !in 0..24 || minute !in 0..59) return null
        val marker = amPm.lowercase(Locale.ENGLISH).filter { it.isLetter() }
        val h = when {
            marker.startsWith("p") && hour < 12 -> hour + 12
            marker.startsWith("a") && hour == 12 -> 0
            marker.isEmpty() && hour in 1..6 -> hour + 12
            hour == 24 -> 0
            else -> hour
        }
        return LocalTime.of(h, minute)
    }

    private fun partOfDay(word: String): LocalTime = when (word.lowercase(Locale.ENGLISH)) {
        "morning" -> LocalTime.of(9, 0)
        "afternoon" -> LocalTime.of(15, 0)
        "evening" -> LocalTime.of(18, 0)
        else -> LocalTime.of(21, 0) // night
    }

    private fun number(text: String): Long {
        val word = text.lowercase(Locale.ENGLISH)
        return word.toLongOrNull() ?: (NUMBER_WORDS.indexOf(word).takeIf { it >= 0 }?.toLong() ?: 1L)
    }

    private val NUMBER_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    private const val WEEKDAYS = "monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun"
    private const val MONTHS = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private const val NUMBER = "\\d{1,2}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val HOUR = "\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"

    private val EVERY_WEEKDAY = Regex("\\b(?:every|each)\\s+($WEEKDAYS)s?\\b", RegexOption.IGNORE_CASE)
    private val EVERY_PART_OF_DAY = Regex("\\b(?:every|each)\\s+(morning|afternoon|evening|night)\\b", RegexOption.IGNORE_CASE)
    private val DAILY = Regex("\\b(?:every\\s+day|everyday|each\\s+day|daily)\\b", RegexOption.IGNORE_CASE)
    private val WEEKLY = Regex("\\b(?:every\\s+week|each\\s+week|weekly)\\b", RegexOption.IGNORE_CASE)
    private val MONTHLY = Regex("\\b(?:every\\s+month|each\\s+month|monthly)\\b", RegexOption.IGNORE_CASE)

    private val TIME_AT = Regex("\\bat\\s+($HOUR)(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?![\\d:])", RegexOption.IGNORE_CASE)
    private val TIME_AM_PM = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b", RegexOption.IGNORE_CASE)
    private val TIME_OCLOCK = Regex("\\b($HOUR)\\s*o'?clock\\b", RegexOption.IGNORE_CASE)
    private val NOON = Regex("\\b(?:at\\s+)?(?:noon|midday)\\b", RegexOption.IGNORE_CASE)
    private val MIDNIGHT = Regex("\\b(?:at\\s+)?midnight\\b", RegexOption.IGNORE_CASE)
    private val PART_OF_DAY = Regex("\\bin\\s+the\\s+(morning|afternoon|evening|night)\\b", RegexOption.IGNORE_CASE)

    private val DAY_MONTH = Regex("\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+($MONTHS)\\b", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("\\b(?:on\\s+)?($MONTHS)\\s+(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE)
    private val NUMERIC_DATE = Regex("\\b(?:on\\s+)?(\\d{1,2})[./](\\d{1,2})\\b(?![./:]\\d)", RegexOption.IGNORE_CASE)
    private val DAY_OF_MONTH = Regex("\\b(?:on\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
    private val IN_SOME_TIME = Regex("\\bin\\s+($NUMBER)\\s+(days?|weeks?|months?)\\b", RegexOption.IGNORE_CASE)
    private val NEXT_WEEKDAY = Regex("\\bnext\\s+($WEEKDAYS)\\b", RegexOption.IGNORE_CASE)
    private val WEEKDAY = Regex("\\b(?:on\\s+|this\\s+|coming\\s+)?($WEEKDAYS)\\b", RegexOption.IGNORE_CASE)
    private val NEXT_WEEK = Regex("\\bnext\\s+week\\b", RegexOption.IGNORE_CASE)
    private val DAY_AFTER_TOMORROW = Regex("\\b(?:the\\s+)?day\\s+after\\s+tomorrow\\b", RegexOption.IGNORE_CASE)
    private val TOMORROW = Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE)
    private val TONIGHT = Regex("\\btonight\\b", RegexOption.IGNORE_CASE)
    private val TODAY = Regex("\\btoday\\b", RegexOption.IGNORE_CASE)

    private val WHITESPACE = Regex("\\s+")
    private val LEADING_FILLER = Regex(
        "^(?:please\\s+)?(?:remind\\s+me\\s+to|remind\\s+me|add\\s+a\\s+task\\s+to|add\\s+a\\s+task|add\\s+task|new\\s+task|task|" +
            "i\\s+need\\s+to|i\\s+have\\s+to|don'?t\\s+forget\\s+to|don'?t\\s+forget|remember\\s+to)\\b[\\s:,-]*",
        RegexOption.IGNORE_CASE,
    )

    /** "Call the bank on", once "Thursday" is gone: the connector that led to it goes too. */
    private val DANGLING_WORD_AT_END = Regex("\\s+(?:on|at|in|by|the|for|to|of|and)$", RegexOption.IGNORE_CASE)
}
