package io.github.meko123456.ardaivitsko.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * The days a task falls on. Each repeat is counted from the first day, never from the previous
 * occurrence, so a monthly task from the 31st clamps to a short month's last day and comes back to
 * the 31st after it: 31 Jan, 28 Feb (29 in a leap year), 31 Mar, 30 Apr.
 */
object Repeats {

    /** Whether a task first on [first] with [repeat] falls on [date]. */
    fun occursOn(first: LocalDate, repeat: Repeat, date: LocalDate): Boolean = days(first, repeat, date, date).isNotEmpty()

    /** The days of the series from [from] to [to], both included, in order. */
    fun days(first: LocalDate, repeat: Repeat, from: LocalDate, to: LocalDate): List<LocalDate> {
        val start = maxOf(first, from)
        if (start > to) return emptyList()
        return when (repeat) {
            Repeat.NEVER -> if (first in from..to) listOf(first) else emptyList()
            Repeat.DAILY -> generateSequence(start) { it.plusDays(1) }.takeWhile { it <= to }.toList()
            Repeat.WEEKLY -> {
                val weeks = (ChronoUnit.DAYS.between(first, start) + 6) / 7
                generateSequence(first.plusWeeks(weeks)) { it.plusWeeks(1) }.takeWhile { it <= to }.toList()
            }
            Repeat.MONTHLY -> {
                val skipped = ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(start))
                generateSequence(skipped) { it + 1 }
                    .map { first.plusMonths(it) }
                    .takeWhile { it <= to }
                    .filter { it >= start }
                    .toList()
            }
        }
    }

    /**
     * The days that need an occurrence now: the task's own [first] day always, even when it's past
     * (created late, it's overdue at once), and the series days from [seriesFrom] (the day it was
     * created, or last re-planned) on. [after] is the last day already done; the series is followed
     * up to [through], or to the first day if that's later.
     */
    fun daysToMaterialize(
        first: LocalDate,
        repeat: Repeat,
        seriesFrom: LocalDate,
        after: LocalDate?,
        through: LocalDate,
    ): List<LocalDate> {
        val from = after?.plusDays(1) ?: first
        return days(first, repeat, from, maxOf(through, first)).filter { it == first || it >= seriesFrom }
    }
}
