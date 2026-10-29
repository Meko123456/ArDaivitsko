package io.github.meko123456.ardaivitsko.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Why a nudge comes. When two fall on the same instant, only the stronger one comes. */
enum class NudgeKind(internal val strength: Int) {
    /** On the occurrence's own day: the start-time grid, or the cadence after In progress or Snooze. */
    REGULAR(0),

    /** On a later day, while it's still not done. */
    OVERDUE(0),

    /** Can't miss: 19:00 the evening before, "Tomorrow is the last day". */
    HEADS_UP(1),

    /** Can't miss: every :00 and :30 of the last three hours before the deadline. */
    FINAL(2),

    /** Can't miss: the first final slot within the last hour, as a full-screen alarm. */
    ALARM(3),

    /** Can't miss: at the deadline itself. */
    DEADLINE_PASSED(4),
}

data class Nudge(val at: Instant, val kind: NudgeKind)

/** Quiet from [start] (included) to [end] (not included), across midnight when [end] comes first. */
data class QuietHours(val start: LocalTime, val end: LocalTime) {
    operator fun contains(time: LocalTime): Boolean = when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }

    companion object {
        val DEFAULT = QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0))
    }
}

/** The settings that change when nudges come. No quiet hours at all is `null`. */
data class NudgeSettings(val quietHours: QuietHours? = QuietHours.DEFAULT)

/**
 * Works out when an occurrence nudges, as a pure function of the occurrence, the settings and the
 * time zone (docs/behaviour.md, "Nudges").
 *
 * All times are wall-clock times in [zone]: a grid time that doesn't exist (the hour skipped in
 * spring) moves forward by the gap, and one that happens twice (autumn) is the first, which is
 * what [ZonedDateTime.of] does. The cadence after In progress or Snooze is counted on the wall
 * clock too.
 *
 * - On its day, an open occurrence nudges at the start time and every interval after, outside
 *   quiet hours and not before the task was created. In progress doubles the interval from the
 *   moment it's marked; Snooze moves the next nudge to an hour after the tap, and the cadence
 *   carries on from there. Both reach to the end of the day they were tapped.
 * - After its day it's overdue: every day, the grid from the start time again (twice the interval
 *   while In progress).
 * - Can't miss adds a heads-up at 19:00 the evening before, a nudge on every :00 and :30 of the
 *   last three hours (replacing the others there, quiet hours or not), the alarm (the first of
 *   those within the last hour) and "Deadline passed" at the deadline. Quiet hours never silence
 *   these. A snooze holds the final slots back for an hour, except the alarm.
 */
class NudgePlanner(private val settings: NudgeSettings, private val zone: ZoneId) {

    /** Every nudge the occurrence gets, oldest first, through the end of the local date [through]. */
    fun plan(occurrence: Occurrence, through: LocalDate): List<Nudge> =
        nudges(occurrence, occurrence.day, through).filter { it.at.localDate() <= through }

    /** The first nudge strictly after [after], or null when no more will come. */
    fun next(occurrence: Occurrence, after: Instant): Nudge? {
        val afterDate = after.localDate()
        val from = maxOf(occurrence.day, afterDate.minusDays(1))
        // Every overdue day repeats the same grid, so a week without a nudge means there won't be one.
        val to = maxOf(from, afterDate).plusDays(LOOKAHEAD_DAYS)
        return nudges(occurrence, from, to).firstOrNull { it.at > after }
    }

    /** The nudges after [after] (from the start when null) up to and including [until]. */
    fun between(occurrence: Occurrence, after: Instant?, until: Instant): List<Nudge> {
        val from = if (after == null) occurrence.day else maxOf(occurrence.day, after.localDate().minusDays(1))
        val to = until.localDate().plusDays(1)
        return nudges(occurrence, from, to).filter { (after == null || it.at > after) && it.at <= until }
    }

    private fun nudges(occurrence: Occurrence, fromDate: LocalDate, toDate: LocalDate): List<Nudge> {
        if (occurrence.state == OccurrenceState.DONE) return emptyList()
        val found = ArrayList<Nudge>()
        val window = if (occurrence.cantMiss) FinalWindow(occurrence) else null
        var date = maxOf(fromDate, occurrence.day)
        while (date <= toDate) {
            dayNudges(occurrence, date, window, found)
            date = date.plusDays(1)
        }
        if (window != null) cantMissNudges(occurrence, window, found)
        return found
            .filter { it.at >= occurrence.createdAt }
            .sortedWith(compareBy<Nudge> { it.at }.thenByDescending { it.kind.strength })
            .distinctBy { it.at }
    }

    /** The regular (or overdue) nudges of one local date: the grid, then the cadence after each tap. */
    private fun dayNudges(occurrence: Occurrence, date: LocalDate, window: FinalWindow?, out: MutableList<Nudge>) {
        val kind = if (date == occurrence.day) NudgeKind.REGULAR else NudgeKind.OVERDUE
        val interval = occurrence.intervalHours.toLong()
        val inProgress = occurrence.state == OccurrenceState.IN_PROGRESS
        val markedAt = if (inProgress) occurrence.inProgressSince else null
        val markedOn = markedAt?.localDate()
        // Before the day it was marked In progress, it was open and kept the plain interval.
        val gridStep = if (inProgress && (markedOn == null || markedOn < date)) 2 * interval else interval

        val anchors = buildList {
            if (markedAt != null && markedOn == date) add(Anchor(markedAt, snooze = false))
            val snoozedAt = occurrence.snoozedAt
            if (snoozedAt != null && snoozedAt.localDate() == date) add(Anchor(snoozedAt, snooze = true))
        }.sortedBy { it.at }

        fun offer(at: Instant) {
            if (window != null && window.covers(at)) return // the final three hours replace these
            if (isQuiet(at)) return
            out += Nudge(at, kind)
        }

        val firstAnchor = anchors.firstOrNull()?.at
        var time: LocalDateTime = date.atTime(occurrence.startTime)
        while (time.toLocalDate() == date) {
            val at = time.atZone(zone).toInstant()
            if (firstAnchor == null || at < firstAnchor) offer(at)
            time = time.plusHours(gridStep)
        }

        anchors.forEachIndexed { index, anchor ->
            val until = anchors.getOrNull(index + 1)?.at
            val step = when {
                !anchor.snooze -> 2 * interval
                // A snooze keeps the In progress pace if it came after (or on a day after) the mark.
                inProgress && (markedAt == null || markedOn!! < date || markedAt <= anchor.at) -> 2 * interval
                else -> interval
            }
            var next = wallClock(anchor.at).plusHours(if (anchor.snooze) 1 else step)
            while (next.toLocalDate() == date) {
                val at = next.atZone(zone).toInstant()
                if (until != null && at >= until) break
                offer(at)
                next = next.plusHours(step)
            }
        }
    }

    /** The heads-up, the final three hours with the alarm, and "Deadline passed". */
    private fun cantMissNudges(occurrence: Occurrence, window: FinalWindow, out: MutableList<Nudge>) {
        out += Nudge(ZonedDateTime.of(occurrence.day.minusDays(1), HEADS_UP_TIME, zone).toInstant(), NudgeKind.HEADS_UP)

        val slots = finalSlots(window)
        val alarm = slots.firstOrNull { it >= window.lastHour && it >= occurrence.createdAt }

        // A snooze holds the final slots back until an hour after the tap, then nudges once, and the
        // slots carry on. A later In progress tap ends it there. The alarm is never held back.
        val snooze = occurrence.snoozedAt?.let { snoozedAt ->
            val markedLater = occurrence.inProgressSince
                ?.takeIf { occurrence.state == OccurrenceState.IN_PROGRESS && it > snoozedAt }
            Snooze(at = snoozedAt, next = wallClock(snoozedAt).plusHours(1).atZone(zone).toInstant(), endedAt = markedLater)
        }
        for (slot in slots) {
            when {
                slot == alarm -> out += Nudge(slot, NudgeKind.ALARM)
                snooze != null && snooze.holdsBack(slot) -> Unit
                else -> out += Nudge(slot, NudgeKind.FINAL)
            }
        }
        if (snooze != null && snooze.endedAt.let { it == null || snooze.next < it } && window.covers(snooze.next)) {
            out += Nudge(snooze.next, NudgeKind.FINAL)
        }
        out += Nudge(window.deadline, NudgeKind.DEADLINE_PASSED)
    }

    /** Every :00 and :30, local time, from three hours before the deadline up to (not at) it. */
    private fun finalSlots(window: FinalWindow): List<Instant> {
        val slots = ArrayList<Instant>()
        var time = window.start.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        while (time.toInstant() < window.deadline) {
            val at = time.toInstant()
            if (at >= window.start && time.minute % 30 == 0) slots += at
            time = time.plusMinutes(30)
        }
        return slots
    }

    private fun isQuiet(at: Instant): Boolean {
        val quietHours = settings.quietHours ?: return false
        return at.atZone(zone).toLocalTime() in quietHours
    }

    private fun Instant.localDate(): LocalDate = atZone(zone).toLocalDate()

    private fun wallClock(at: Instant): LocalDateTime = LocalDateTime.ofInstant(at, zone)

    private inner class FinalWindow(occurrence: Occurrence) {
        val deadline: Instant = ZonedDateTime.of(occurrence.day, occurrence.deadline, zone).toInstant()
        val start: Instant = deadline.minus(Duration.ofHours(3))
        val lastHour: Instant = deadline.minus(Duration.ofHours(1))
        fun covers(at: Instant) = at >= start && at < deadline
    }

    private class Anchor(val at: Instant, val snooze: Boolean)

    private class Snooze(val at: Instant, val next: Instant, val endedAt: Instant?) {
        fun holdsBack(slot: Instant) = slot > at && slot < next && (endedAt == null || slot < endedAt)
    }

    companion object {
        val HEADS_UP_TIME: LocalTime = LocalTime.of(19, 0)
        private const val LOOKAHEAD_DAYS = 8L
    }
}
