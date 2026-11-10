package io.github.meko123456.ardaivitsko.calendar

import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Repeats
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * One side of a link, in the terms both sides share: the title, the day, the time (null when
 * all-day) and whether it's marked done. The app marks a done task's event with "✓ " in front of
 * its title, since a calendar event has no done of its own.
 */
data class SyncedEvent(val title: String, val day: LocalDate, val time: LocalTime?, val done: Boolean) {
    /** The same without the done mark, for telling a renamed or moved event from a ticked one. */
    val body: Triple<String, LocalDate, LocalTime?> get() = Triple(title, day, time)
}

/** Between a task and a calendar event, in both directions. */
object EventMapping {
    const val DONE_MARK = "✓ "

    /** What the event says. An all-day event's day is its UTC date; a timed one's, in the phone's zone. */
    fun eventSide(event: CalendarEvent, zone: ZoneId): SyncedEvent {
        val done = event.title.startsWith(DONE_MARK)
        val title = event.title.removePrefix(DONE_MARK).trim()
        return if (event.allDay) {
            SyncedEvent(title, event.start.atZone(ZoneOffset.UTC).toLocalDate(), null, done)
        } else {
            val local = event.start.atZone(zone)
            SyncedEvent(title, local.toLocalDate(), local.toLocalTime(), done)
        }
    }

    /**
     * What the task says, as an event: due on [day] (a one-off's occurrence follows its moves),
     * at its deadline, or all-day when the deadline is the day's end.
     */
    fun taskSide(task: Task, day: LocalDate, done: Boolean): SyncedEvent =
        SyncedEvent(task.title, day, task.deadline.takeUnless { it == TaskDefaults.DEADLINE }, done)

    fun eventDraft(task: Task, day: LocalDate, done: Boolean): EventDraft {
        val side = taskSide(task, day, done)
        return EventDraft(
            title = (if (done) DONE_MARK else "") + side.title,
            note = task.note,
            day = side.day,
            time = side.time,
            repeat = task.repeat,
        )
    }

    /**
     * A task from an event in a watched calendar, or null for one with no title. A repeating event
     * starts here on its next day from [today] on, not on its first day years ago, which would be
     * overdue at once. An all-day event is due by the day's end; a timed one, at its time.
     */
    fun importedTask(event: CalendarEvent, today: LocalDate, zone: ZoneId, defaults: TaskDefaults, now: Instant): Task? {
        val side = eventSide(event, zone)
        if (side.title.isBlank()) return null
        val repeat = repeatOf(event.rrule)
        val day = if (repeat == Repeat.NEVER) {
            side.day
        } else {
            Repeats.days(side.day, repeat, from = maxOf(side.day, today), to = maxOf(side.day, today).plusYears(1)).firstOrNull() ?: side.day
        }
        return Task(
            title = side.title,
            note = event.note.trim(),
            day = day,
            deadline = side.time ?: TaskDefaults.DEADLINE,
            repeat = repeat,
            startTime = defaults.startTime,
            intervalHours = defaults.intervalHours,
            createdAt = now,
        )
    }

    /**
     * The repeats the app has: every day, every week on the same weekday, every month on the same
     * day. Anything else (every second week, several weekdays, yearly) comes once, on its first day.
     */
    fun repeatOf(rrule: String?): Repeat {
        if (rrule.isNullOrBlank()) return Repeat.NEVER
        val parts = rrule.split(';').mapNotNull { part ->
            val (key, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            key.trim().uppercase(Locale.ENGLISH) to value.trim().uppercase(Locale.ENGLISH)
        }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        if (interval != 1) return Repeat.NEVER
        return when (parts["FREQ"]) {
            "DAILY" -> Repeat.DAILY
            "WEEKLY" -> if ((parts["BYDAY"]?.split(',')?.size ?: 1) == 1) Repeat.WEEKLY else Repeat.NEVER
            "MONTHLY" -> if ((parts["BYMONTHDAY"]?.split(',')?.size ?: 1) == 1 && parts["BYDAY"] == null) Repeat.MONTHLY else Repeat.NEVER
            else -> Repeat.NEVER
        }
    }

    /** The rule for a repeating task from [day]; null for a one-off. */
    fun rrule(repeat: Repeat, day: LocalDate): String? = when (repeat) {
        Repeat.NEVER -> null
        Repeat.DAILY -> "FREQ=DAILY"
        Repeat.WEEKLY -> "FREQ=WEEKLY;BYDAY=" + day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(2).uppercase(Locale.ENGLISH)
        Repeat.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=${day.dayOfMonth}"
    }
}
