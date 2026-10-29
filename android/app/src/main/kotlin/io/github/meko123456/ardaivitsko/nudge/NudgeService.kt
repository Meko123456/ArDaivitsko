package io.github.meko123456.ardaivitsko.nudge

import io.github.meko123456.ardaivitsko.core.Answers
import io.github.meko123456.ardaivitsko.core.Nudge
import io.github.meko123456.ardaivitsko.core.NudgePlanner
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.core.deliver
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

/** Posts and removes the notifications of one occurrence. */
interface NudgeNotifier {
    /** Posts [nudge] for [item]. [silent] re-posts without sound, never as a full-screen alarm. */
    fun post(item: OccurrenceItem, nudge: Nudge, now: Instant, silent: Boolean = false)

    /** "Moved to tomorrow", with Undo. */
    fun postMoved(item: OccurrenceItem)

    fun cancel(occurrenceId: Long)
}

/** One pending alarm per occurrence: its next nudge. Plus a tick at midnight. */
interface NudgeAlarms {
    fun set(occurrenceId: Long, nudge: Nudge)

    fun cancel(occurrenceId: Long)

    fun setDayTick(at: Instant)
}

fun interface WidgetRefresher {
    fun refresh()
}

/** An answer to one occurrence, from a notification, the widget, the alarm or a screen. */
sealed interface Answer {
    data object Done : Answer
    data object InProgress : Answer
    data object NotToday : Answer
    data object Snooze : Answer
    data object Skip : Answer
    data class MoveTo(val day: LocalDate) : Answer
    data object UndoMove : Answer
    data object Reopen : Answer
}

/**
 * Everything that changes what's scheduled goes through here, one change at a time.
 *
 * [reconcile] is the whole of scheduling: it writes out the coming days' occurrences, posts each
 * occurrence's due nudge (one catch-up when several were missed), and sets one alarm per
 * occurrence for its next nudge. Only a task's latest day so far nudges; its earlier open days
 * are quiet (OneNudgePerTask). It runs after every alarm, answer and edit, at midnight, on boot,
 * on a clock, time zone or app update, and whenever the app opens, so a lost alarm is only ever
 * lost until the next of those.
 */
class NudgeService(
    private val store: TaskStore,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val notifier: NudgeNotifier,
    private val alarms: NudgeAlarms,
    private val widget: WidgetRefresher,
) {
    private val mutex = Mutex()

    suspend fun reconcile() = mutex.withLock { reconcileLocked() }

    /** Applies [answer] to an occurrence and reschedules. Returns it as it is now, or null if it's gone. */
    suspend fun answer(occurrenceId: Long, answer: Answer): OccurrenceItem? = mutex.withLock {
        val item = store.occurrence(occurrenceId)
        if (item == null) {
            // A notification or widget row for something deleted since: clear it away.
            notifier.cancel(occurrenceId)
            alarms.cancel(occurrenceId)
            reconcileLocked()
            return@withLock null
        }
        val now = time.now()
        val today = now.atZone(time.zone()).toLocalDate()
        val status = when (answer) {
            Answer.Done -> Answers.done(item.status, now)
            Answer.InProgress -> Answers.inProgress(item.status, now)
            Answer.NotToday -> Answers.notToday(item.status, today)
            Answer.Snooze -> Answers.snooze(item.status, now)
            Answer.Skip -> Answers.skip(item.status, now)
            is Answer.MoveTo -> Answers.moveTo(item.status, answer.day)
            Answer.UndoMove -> Answers.undoMove(item.status)
            Answer.Reopen -> Answers.reopen(item.status)
        }
        store.saveStatus(occurrenceId, status, answeredAt = now)
        notifier.cancel(occurrenceId)
        if (status.state.isClosed) alarms.cancel(occurrenceId)
        reconcileLocked()
        store.occurrence(occurrenceId)
    }

    /** Saves a new task, created now, and schedules it. Returns its id. */
    suspend fun addTask(task: Task): Long = mutex.withLock {
        val now = time.now()
        val today = now.atZone(time.zone()).toLocalDate()
        val id = store.addTask(task.copy(createdAt = now), today, through = today.plusDays(DAYS_AHEAD))
        reconcileLocked()
        id
    }

    suspend fun updateTask(task: Task) = mutex.withLock {
        val now = time.now()
        val today = now.atZone(time.zone()).toLocalDate()
        val removed = store.updateTask(task, today, through = today.plusDays(DAYS_AHEAD), now = now)
        removed.forEach(::forget)
        reconcileLocked()
    }

    suspend fun deleteTask(taskId: Long) = mutex.withLock {
        store.deleteTask(taskId).forEach(::forget)
        reconcileLocked()
    }

    /** New quiet hours count from now: nothing they would have let through earlier fires as a catch-up. */
    suspend fun settingsChanged() = mutex.withLock {
        store.acknowledgeAll(time.now())
        reconcileLocked()
    }

    private fun forget(occurrenceId: Long) {
        notifier.cancel(occurrenceId)
        alarms.cancel(occurrenceId)
    }

    private suspend fun reconcileLocked() {
        val now = time.now()
        val zone = time.zone()
        val today = now.atZone(zone).toLocalDate()
        // Tomorrow's occurrences exist a day ahead, for a Can't-miss task's heads-up the evening before.
        store.materialize(through = today.plusDays(DAYS_AHEAD))
        val planner = NudgePlanner(settings.current().nudgeSettings, zone)
        // One nudge per task: a repeating task's earlier open days stay listed but quiet, with no
        // nudge, no alarm and no catch-up, and lose any notification still showing (yesterday's,
        // once today's day has come).
        val latestDays = store.latestDays(today)
        val (quiet, nudging) = store.undone().partition { it.isQuiet(latestDays) }
        for (item in quiet) {
            notifier.cancel(item.id)
            alarms.cancel(item.id)
        }
        val deliveries = nudging.map { it to planner.deliver(it.occurrence, it.lastNudgedAt, now) }
        // Android mutes an app's later notifications in a burst, so the strongest (a Can't-miss alarm)
        // goes first when several are due at once.
        for ((item, delivery) in deliveries.sortedByDescending { it.second.post?.kind?.strength ?: -1 }) {
            delivery.post?.let { notifier.post(item, it, now) }
        }
        for ((item, delivery) in deliveries) {
            if (delivery.lastNudgedAt != item.lastNudgedAt) store.setLastNudged(item.id, delivery.lastNudgedAt)
            val next = delivery.next
            if (next != null) alarms.set(item.id, next) else alarms.cancel(item.id)
        }
        alarms.setDayTick(today.plusDays(1).atStartOfDay(zone).toInstant())
        widget.refresh()
    }

    private companion object {
        const val DAYS_AHEAD = 1L
    }
}
