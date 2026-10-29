package io.github.meko123456.ardaivitsko.core

import java.time.LocalDate

/**
 * One nudge per task (docs/behaviour.md, Merab's ruling): a repeating task nudges only for its
 * latest day so far, today's occurrence or the most recent one before today. Its earlier days that
 * are still open stay listed as overdue, but quietly: no nudges, no alarms, no catch-up. Days still
 * to come aren't affected, so a Can't-miss task's heads-up the evening before still comes.
 *
 * Days are the ones each occurrence's series gave it, not where a move put it: after Not today,
 * today's occurrence is still the latest (so an older open day doesn't wake up), and once the
 * next day of the series comes, the moved one falls quiet.
 */
object OneNudgePerTask {

    /** A task's latest day so far: the latest of its occurrences' days on or before [today], if any. */
    fun latestDay(originalDays: Iterable<LocalDate>, today: LocalDate): LocalDate? =
        originalDays.filter { it <= today }.maxOrNull()

    /** Whether an occurrence planned for [originalDay] is quiet, given its task's [latestDay]. */
    fun isQuiet(originalDay: LocalDate, latestDay: LocalDate?): Boolean =
        latestDay != null && originalDay < latestDay
}
