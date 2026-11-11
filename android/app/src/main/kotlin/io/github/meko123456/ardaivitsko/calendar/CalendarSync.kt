package io.github.meko123456.ardaivitsko.calendar

import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Google Calendar channel, through the phone's calendar provider.
 *
 * - Events in the watched calendars become tasks here (once each; the link remembers which).
 * - A spoken task can be written to the chosen calendar as an event ([addEvent]).
 * - A linked one-off task and its event follow each other: Move to… or Not today moves the
 *   event, Done puts "✓ " in front of its title (and Mark as not done takes it off), and an
 *   event renamed or moved in the calendar renames or moves the task. When both changed since
 *   they last agreed, the task wins. A repeating task and its event share only their title.
 * - A deleted event leaves its task, without the link; a deleted task leaves its event.
 *
 * [syncNow] does all of it, under one lock; [requestSync] does it soon, once for a burst of
 * changes. It runs when the app opens, after every change to a task, when the calendar settings
 * change, and every fifteen minutes in the background while anything is watched.
 */
class CalendarSync(
    private val store: TaskStore,
    private val service: NudgeService,
    private val settings: SettingsStore,
    private val provider: CalendarProvider,
    private val links: CalendarLinks,
    private val time: TimeSource,
    scope: CoroutineScope,
    /** Turns the background check on or off. */
    private val periodic: (on: Boolean) -> Unit,
) {
    private val mutex = Mutex()
    private val requests = MutableStateFlow(0)

    init {
        // A StateFlow hands a busy collector only the latest value: many requests, one more run.
        scope.launch { requests.collect { if (it > 0) syncNow() } }
    }

    fun requestSync() {
        requests.value++
    }

    suspend fun syncNow(): Unit = mutex.withLock {
        val current = settings.current()
        periodic(current.calendar.active)
        if (!provider.hasAccess()) return
        val zone = time.zone()
        val now = time.now()
        val today = now.atZone(zone).toLocalDate()

        for (link in links.all()) {
            val task = store.task(link.taskId)
            val event = provider.event(link.eventId)
            if (task == null || event == null) {
                links.remove(link.taskId)
                continue
            }
            val eventSide = EventMapping.eventSide(event, zone)
            val taskSide = taskSide(task, link.synced)
            if (task.repeat != Repeat.NEVER) {
                // Only the title: a series' days are each side's own.
                when {
                    eventSide.title != link.synced.title && taskSide.title == link.synced.title -> {
                        service.updateTask(task.copy(title = eventSide.title))
                        links.save(link.copy(synced = link.synced.copy(title = eventSide.title)))
                    }
                    taskSide.title != link.synced.title -> {
                        provider.update(link.eventId, EventMapping.eventDraft(task, link.synced.day, done = false).copy(time = link.synced.time), zone)
                        links.save(link.copy(synced = link.synced.copy(title = taskSide.title)))
                    }
                }
                continue
            }
            when {
                eventSide.body != link.synced.body && taskSide.body == link.synced.body -> {
                    // Renamed or moved in the calendar: the task follows. The ✓ stays the app's.
                    service.updateTask(task.copy(title = eventSide.title, day = eventSide.day, deadline = eventSide.time ?: TaskDefaults.DEADLINE))
                    val pulled = eventSide.copy(done = taskSide.done)
                    if (eventSide.done != taskSide.done) {
                        provider.update(link.eventId, EventMapping.eventDraft(task.copy(title = pulled.title, deadline = pulled.time ?: TaskDefaults.DEADLINE), pulled.day, pulled.done), zone)
                    }
                    links.save(link.copy(synced = pulled))
                }
                taskSide != link.synced || eventSide.done != taskSide.done -> {
                    provider.update(link.eventId, EventMapping.eventDraft(task, taskSide.day, taskSide.done), zone)
                    links.save(link.copy(synced = taskSide))
                }
            }
        }

        val watched = current.calendar.watchedCalendarIds
        if (watched.isEmpty()) return
        val linked = links.all().map { it.eventId }.toSet()
        val (from, to) = importWindow(today, zone)
        for (event in provider.events(watched, from, to)) {
            if (event.id in linked || event.title.startsWith(EventMapping.DONE_MARK)) continue
            val task = EventMapping.importedTask(event, today, zone, current.defaults, now) ?: continue
            val taskId = service.addTask(task)
            links.save(CalendarLink(taskId, event.id, event.calendarId, EventMapping.eventSide(event, zone).copy(done = false)))
        }
    }

    /** Writes [taskId] to [calendarId] as a new event and links them. False when the provider refused. */
    suspend fun addEvent(taskId: Long, calendarId: Long): Boolean = mutex.withLock {
        if (!provider.hasAccess()) return false
        val task = store.task(taskId) ?: return false
        val day = if (task.repeat == Repeat.NEVER) store.occurrencesOf(taskId).firstOrNull()?.day ?: task.day else task.day
        val eventId = provider.insert(calendarId, EventMapping.eventDraft(task, day, done = false), time.zone()) ?: return false
        links.save(CalendarLink(taskId, eventId, calendarId, EventMapping.taskSide(task, day, done = false)))
        true
    }

    suspend fun clearLinks(): Unit = mutex.withLock { links.clear() }

    /** A one-off's side comes from its one occurrence: the day it's on now, and whether it's done. */
    private suspend fun taskSide(task: Task, synced: SyncedEvent): SyncedEvent {
        if (task.repeat != Repeat.NEVER) return SyncedEvent(task.title, synced.day, synced.time, false)
        val occurrence = store.occurrencesOf(task.id).firstOrNull()
        val day: LocalDate = occurrence?.day ?: task.day
        return EventMapping.taskSide(task, day, done = occurrence?.state == OccurrenceState.DONE)
    }

    companion object {
        /** How far ahead a watched calendar's events become tasks. */
        private const val DAYS_AHEAD = 366L

        /** The events a watched calendar gives: from yesterday to a year ahead. */
        fun importWindow(today: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
            today.minusDays(1).atStartOfDay(zone).toInstant() to today.plusDays(DAYS_AHEAD).atStartOfDay(zone).toInstant()

        /** How many of [events] would become tasks: the ones not already ticked off. */
        fun importable(events: List<CalendarEvent>): Int = events.count { !it.title.startsWith(EventMapping.DONE_MARK) }
    }
}
