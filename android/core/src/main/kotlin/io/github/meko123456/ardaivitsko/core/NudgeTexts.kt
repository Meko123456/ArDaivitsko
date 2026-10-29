package io.github.meko123456.ardaivitsko.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The words that say why a nudge comes now ("Due today by 23:59", "Overdue since 28 Oct", "Last 30
 * minutes") and when the next one will. Times and dates come from the formatters, so the app can
 * follow the phone's 12/24-hour setting and language while the tests pin both.
 */
class NudgeTexts(
    private val zone: ZoneId,
    private val formatTime: (LocalTime) -> String,
    private val formatDate: (LocalDate) -> String,
) {

    /** The line under the title of a nudge of [kind] for [occurrence], posted at [now]. */
    fun whyNow(kind: NudgeKind, occurrence: Occurrence, now: Instant): String {
        val deadline = ZonedDateTime.of(occurrence.day, occurrence.deadline, zone).toInstant()
        return when (kind) {
            NudgeKind.REGULAR ->
                if (now < deadline) "Due today by ${formatTime(occurrence.deadline)}"
                else "Was due today by ${formatTime(occurrence.deadline)}"
            NudgeKind.OVERDUE -> overdueSince(occurrence.day)
            NudgeKind.HEADS_UP -> "Tomorrow is the last day"
            NudgeKind.FINAL, NudgeKind.ALARM -> lastStretch(Duration.between(now, deadline))
            NudgeKind.DEADLINE_PASSED -> "Deadline passed"
        }
    }

    fun overdueSince(day: LocalDate): String = "Overdue since ${formatDate(day)}"

    /** When an occurrence is due, as the lists show it: "Due by 23:59", or "Overdue since 28 Oct". */
    fun dueLine(day: LocalDate, deadline: LocalTime, today: LocalDate): String = when {
        day < today -> overdueSince(day)
        day == today -> "Due today by ${formatTime(deadline)}"
        day == today.plusDays(1) -> "Due tomorrow by ${formatTime(deadline)}"
        else -> "Due ${formatDate(day)} by ${formatTime(deadline)}"
    }

    /** "Next nudge at 16:00", "Next nudge tomorrow at 10:00", or none left. */
    fun nextNudge(next: Nudge?, now: Instant): String {
        if (next == null) return "No more nudges"
        val at = next.at.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val time = formatTime(at.toLocalTime())
        return when (at.toLocalDate()) {
            today -> "Next nudge at $time"
            today.plusDays(1) -> "Next nudge tomorrow at $time"
            else -> "Next nudge on ${formatDate(at.toLocalDate())} at $time"
        }
    }

    companion object {
        /** The time left, rounded up to the half hour: "Last 3 hours", "Last 2½ hours", "Last hour", "Last 30 minutes". */
        fun lastStretch(left: Duration): String {
            val halfHours = ((left.toMinutes() + 29) / 30).coerceAtLeast(1)
            return when {
                halfHours == 1L -> "Last 30 minutes"
                halfHours == 2L -> "Last hour"
                halfHours % 2 == 0L -> "Last ${halfHours / 2} hours"
                else -> "Last ${halfHours / 2}½ hours"
            }
        }
    }
}
