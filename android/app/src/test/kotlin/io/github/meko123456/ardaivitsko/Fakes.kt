package io.github.meko123456.ardaivitsko

import io.github.meko123456.ardaivitsko.core.Nudge
import io.github.meko123456.ardaivitsko.core.NudgeKind
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.OccurrenceStatus
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Repeats
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskSeries
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.nudge.NudgeAlarms
import io.github.meko123456.ardaivitsko.nudge.NudgeNotifier
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.time.TextFormats
import io.github.meko123456.ardaivitsko.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val TBILISI: ZoneId = ZoneId.of("Asia/Tbilisi")

fun at(text: String, zone: ZoneId = TBILISI): Instant =
    LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant()

fun day(text: String): LocalDate = LocalDate.parse(text)

fun Instant.wallClock(zone: ZoneId = TBILISI): String = atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

class FakeTime(var now: Instant, var zone: ZoneId = TBILISI) : TimeSource {
    override fun now(): Instant = now

    override fun zone(): ZoneId = zone
}

/** 24-hour times and English dates, whatever the machine running the tests uses. */
class FixedFormats : TextFormats {
    override fun is24Hour() = true

    override fun time(time: LocalTime): String = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))

    override fun date(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

    override fun longDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))

    override fun month(month: YearMonth): String = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
}

class FakeSettings(initial: AppSettings = AppSettings()) : SettingsStore {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}

class FakeNotifier : NudgeNotifier {
    data class Posted(val occurrenceId: Long, val kind: NudgeKind, val at: Instant, val silent: Boolean)

    val posted = mutableListOf<Posted>()
    val moved = mutableListOf<Long>()

    /** What's in the shade now, by occurrence. */
    val showing = mutableMapOf<Long, NudgeKind>()

    override fun post(item: OccurrenceItem, nudge: Nudge, now: Instant, silent: Boolean) {
        posted += Posted(item.id, nudge.kind, nudge.at, silent)
        showing[item.id] = nudge.kind
    }

    override fun postMoved(item: OccurrenceItem) {
        moved += item.id
    }

    override fun cancel(occurrenceId: Long) {
        showing.remove(occurrenceId)
    }
}

class FakeAlarms : NudgeAlarms {
    /** The one pending alarm per occurrence. */
    val pending = mutableMapOf<Long, Nudge>()
    var tickAt: Instant? = null

    override fun set(occurrenceId: Long, nudge: Nudge) {
        pending[occurrenceId] = nudge
    }

    override fun cancel(occurrenceId: Long) {
        pending.remove(occurrenceId)
    }

    override fun setDayTick(at: Instant) {
        tickAt = at
    }
}

/** An in-memory TaskStore that follows RoomTaskStore's rules (materializing, moves, re-planning). */
class FakeTaskStore : TaskStore {
    private data class StoredTask(val task: Task, val seriesFrom: LocalDate, val materializedThrough: LocalDate?)
    private data class Row(val id: Long, val taskId: Long, val originalDay: LocalDate, val status: OccurrenceStatus, val lastNudgedAt: Instant?)

    private val tasks = MutableStateFlow<Map<Long, StoredTask>>(emptyMap())
    private val rows = MutableStateFlow<Map<Long, Row>>(emptyMap())
    private var nextTaskId = 1L
    private var nextRowId = 1L

    private val items: Flow<List<OccurrenceItem>> = combine(tasks, rows) { tasks, rows ->
        rows.values
            .filter { it.taskId in tasks }
            .sortedWith(compareBy({ it.status.day }, { it.id }))
            .map { OccurrenceItem(it.id, tasks.getValue(it.taskId).task, it.originalDay, it.status, it.lastNudgedAt) }
    }

    override fun observeActive(today: LocalDate) = items.map { all -> all.filter { (!it.isDone && it.day <= today) || it.day == today } }

    override fun observeBetween(from: LocalDate, to: LocalDate) = items.map { all -> all.filter { it.day in from..to } }

    override fun observeSeries() = tasks.map { all -> all.values.map { TaskSeries(it.task, it.seriesFrom, it.materializedThrough) } }

    override fun observeOccurrence(id: Long) = items.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun occurrence(id: Long) = observeOccurrence(id).first()

    override suspend fun task(id: Long) = tasks.value[id]?.task

    override suspend fun undone() = items.first().filter { !it.isDone }

    override suspend fun addTask(task: Task, today: LocalDate, through: LocalDate): Long {
        val id = nextTaskId++
        tasks.update { it + (id to StoredTask(task.copy(id = id), seriesFrom = today, materializedThrough = null)) }
        materializeTask(id, through, lastNudgedAt = null)
        return id
    }

    override suspend fun updateTask(task: Task, today: LocalDate, through: LocalDate, now: Instant): List<Long> {
        val old = tasks.value[task.id] ?: return emptyList()
        val edited = old.copy(task = task.copy(createdAt = old.task.createdAt))
        val own = rows.value.values.filter { it.taskId == task.id }
        val removed = when {
            old.task.day == task.day && old.task.repeat == task.repeat -> {
                tasks.update { it + (task.id to edited) }
                emptyList()
            }
            old.task.repeat == Repeat.NEVER && task.repeat == Repeat.NEVER && own.size == 1 -> {
                tasks.update { it + (task.id to edited.copy(materializedThrough = maxOf(old.materializedThrough ?: task.day, task.day))) }
                val only = own.single()
                val state = if (only.status.state == OccurrenceState.MOVED) OccurrenceState.OPEN else only.status.state
                rows.update {
                    it + (only.id to only.copy(originalDay = task.day, status = only.status.copy(day = task.day, state = state, movedFrom = null, stateBeforeMove = null, snoozedAt = null)))
                }
                emptyList()
            }
            else -> {
                val stale = own.filter { it.originalDay >= today && it.untouched() }.map { it.id }
                rows.update { it - stale.toSet() }
                tasks.update { it + (task.id to edited.copy(seriesFrom = today, materializedThrough = today.minusDays(1))) }
                materializeTask(task.id, through, lastNudgedAt = now)
                stale
            }
        }
        rows.update { all -> all.mapValues { (_, row) -> if (row.taskId == task.id && row.status.state != OccurrenceState.DONE) row.copy(lastNudgedAt = now) else row } }
        return removed
    }

    override suspend fun deleteTask(taskId: Long): List<Long> {
        val ids = rows.value.values.filter { it.taskId == taskId }.map { it.id }
        rows.update { it - ids.toSet() }
        tasks.update { it - taskId }
        return ids
    }

    override suspend fun materialize(through: LocalDate) {
        tasks.value.keys.forEach { materializeTask(it, through, lastNudgedAt = null) }
    }

    override suspend fun saveStatus(id: Long, status: OccurrenceStatus, answeredAt: Instant) {
        val row = rows.value[id] ?: return
        rows.update { it + (id to row.copy(status = status, lastNudgedAt = answeredAt)) }
        val stored = tasks.value[row.taskId] ?: return
        if (stored.task.repeat == Repeat.NEVER && stored.task.day != status.day) {
            tasks.update {
                it + (row.taskId to stored.copy(task = stored.task.copy(day = status.day), materializedThrough = maxOf(stored.materializedThrough ?: status.day, status.day)))
            }
        }
    }

    override suspend fun setLastNudged(id: Long, at: Instant?) {
        rows.update { all -> all[id]?.let { all + (id to it.copy(lastNudgedAt = at)) } ?: all }
    }

    override suspend fun acknowledgeAll(at: Instant) {
        rows.update { all -> all.mapValues { (_, row) -> if (row.status.state != OccurrenceState.DONE) row.copy(lastNudgedAt = at) else row } }
    }

    /** Test setup: an occurrence's status as if answered long ago, without touching lastNudgedAt. */
    fun overwrite(id: Long, transform: (OccurrenceStatus) -> OccurrenceStatus) {
        rows.update { all -> all + (id to all.getValue(id).let { it.copy(status = transform(it.status)) }) }
    }

    private fun materializeTask(taskId: Long, through: LocalDate, lastNudgedAt: Instant?) {
        val stored = tasks.value[taskId] ?: return
        val days = Repeats.daysToMaterialize(stored.task.day, stored.task.repeat, stored.seriesFrom, stored.materializedThrough, through)
        val existing = rows.value.values.filter { it.taskId == taskId }.map { it.originalDay }.toSet()
        for (d in days.filter { it !in existing }) {
            val id = nextRowId++
            rows.update { it + (id to Row(id, taskId, d, OccurrenceStatus(day = d), lastNudgedAt)) }
        }
        val newThrough = maxOf(through, stored.task.day)
        if (stored.materializedThrough == null || newThrough > stored.materializedThrough) {
            tasks.update { it + (taskId to stored.copy(materializedThrough = newThrough)) }
        }
    }

    private fun Row.untouched() =
        status.state == OccurrenceState.OPEN && status.inProgressSince == null && status.snoozedAt == null && status.movedFrom == null && status.doneAt == null
}

/** A NudgeService over fakes, with everything a test wants to look at. */
class Harness(now: Instant, settings: AppSettings = AppSettings()) {
    val time = FakeTime(now)
    val store = FakeTaskStore()
    val settings = FakeSettings(settings)
    val notifier = FakeNotifier()
    val alarms = FakeAlarms()
    var widgetRefreshes = 0
    val formats = FixedFormats()
    val service = NudgeService(store, this.settings, time, notifier, alarms) { widgetRefreshes++ }

    suspend fun add(task: Task): Long {
        service.addTask(task)
        return store.undone().last { it.task.title == task.title }.id
    }

    /** Moves the clock and lets the alarm (or a boot, or the app opening) do its work. */
    suspend fun advanceTo(text: String) {
        time.now = at(text, time.zone)
        service.reconcile()
    }

    fun nextAlarm(occurrenceId: Long): String? = alarms.pending[occurrenceId]?.let { "${it.at.wallClock(time.zone)} ${it.kind.name.lowercase()}" }
}

fun task(
    title: String = "Pay the credit card bill",
    day: String = "2026-10-29",
    cantMiss: Boolean = false,
    repeat: Repeat = Repeat.NEVER,
    deadline: String = "23:59",
    start: String = "10:00",
    interval: Int = 3,
) = Task(
    title = title,
    day = day(day),
    cantMiss = cantMiss,
    repeat = repeat,
    deadline = LocalTime.parse(deadline),
    startTime = LocalTime.parse(start),
    intervalHours = interval,
    createdAt = Instant.EPOCH,
)
