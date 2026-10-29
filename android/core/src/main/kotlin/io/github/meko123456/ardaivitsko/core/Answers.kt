package io.github.meko123456.ardaivitsko.core

import java.time.Instant
import java.time.LocalDate

/**
 * What each answer does to an occurrence (docs/behaviour.md, "Answers"). Answers to a finished
 * occurrence change nothing (a stale notification or widget tap), except [reopen].
 */
object Answers {

    /** Done: its nudges stop. */
    fun done(status: OccurrenceStatus, now: Instant): OccurrenceStatus =
        if (status.state == OccurrenceState.DONE) status
        else status.copy(state = OccurrenceState.DONE, doneAt = now, movedFrom = null, stateBeforeMove = null)

    /** In progress: half as often from now on. Tapping it again starts the cadence again from the new tap. */
    fun inProgress(status: OccurrenceStatus, now: Instant): OccurrenceStatus =
        if (status.state == OccurrenceState.DONE) status
        else status.copy(
            state = OccurrenceState.IN_PROGRESS,
            inProgressSince = now,
            movedFrom = null,
            stateBeforeMove = null,
        )

    /** Snooze 1 hour: the next nudge comes an hour after [now]. */
    fun snooze(status: OccurrenceStatus, now: Instant): OccurrenceStatus =
        if (status.state == OccurrenceState.DONE) status else status.copy(snoozedAt = now)

    /** Whether Not today applies: the occurrence is for [today] or overdue, not still ahead. */
    fun canMoveToTomorrow(status: OccurrenceStatus, today: LocalDate): Boolean =
        status.state != OccurrenceState.DONE && status.day <= today

    /**
     * Not today: the occurrence moves to the day after [today] (counted from today, also when it's
     * overdue), keeping its deadline time. It becomes Moved, so it nudges like Open there.
     */
    fun notToday(status: OccurrenceStatus, today: LocalDate): OccurrenceStatus =
        if (!canMoveToTomorrow(status, today)) status else moveTo(status, today.plusDays(1))

    /** Move to…: the occurrence belongs to [day] from now on. */
    fun moveTo(status: OccurrenceStatus, day: LocalDate): OccurrenceStatus =
        if (status.state == OccurrenceState.DONE || day == status.day) status
        else status.copy(
            day = day,
            state = OccurrenceState.MOVED,
            movedFrom = status.day,
            stateBeforeMove = status.state,
        )

    /** Undo the last move: back to the day and state it had. */
    fun undoMove(status: OccurrenceStatus): OccurrenceStatus {
        val movedFrom = status.movedFrom ?: return status
        if (status.state != OccurrenceState.MOVED) return status
        return status.copy(
            day = movedFrom,
            state = status.stateBeforeMove ?: OccurrenceState.OPEN,
            movedFrom = null,
            stateBeforeMove = null,
        )
    }

    /** Not done after all: open again, nudging from now on. */
    fun reopen(status: OccurrenceStatus): OccurrenceStatus =
        if (status.state != OccurrenceState.DONE) status
        else status.copy(state = OccurrenceState.OPEN, doneAt = null)

    /**
     * On a Can't-miss task's deadline day, Not today asks first ("This is the last day. Move it
     * anyway?") instead of moving it straight from the notification.
     */
    fun notTodayNeedsConfirmation(task: Task, status: OccurrenceStatus, today: LocalDate): Boolean =
        task.cantMiss && status.day == today
}
