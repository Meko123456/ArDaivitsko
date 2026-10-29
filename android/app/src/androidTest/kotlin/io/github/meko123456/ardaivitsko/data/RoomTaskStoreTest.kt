package io.github.meko123456.ardaivitsko.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.meko123456.ardaivitsko.core.Answers
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RoomTaskStoreTest {

    private lateinit var database: AppDatabase
    private lateinit var store: RoomTaskStore

    private val today = LocalDate.parse("2026-10-29")
    private val tomorrow = today.plusDays(1)
    private val now = Instant.parse("2026-10-29T07:00:00Z")

    @Before
    fun open() {
        database = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomTaskStore(database)
    }

    @After
    fun close() = database.close()

    private fun task(title: String, day: LocalDate = today, repeat: Repeat = Repeat.NEVER) =
        Task(title = title, day = day, repeat = repeat, createdAt = now)

    private suspend fun days(title: String) = store.undone().filter { it.task.title == title }.map { it.day }

    @Test
    fun a_one_off_task_gets_its_occurrence_however_far_ahead_and_only_one() = runTest {
        store.addTask(task("Christmas cards", day = LocalDate.parse("2026-12-24")), today, through = tomorrow)
        store.materialize(through = LocalDate.parse("2027-01-31"))

        assertEquals(listOf(LocalDate.parse("2026-12-24")), days("Christmas cards"))
    }

    @Test
    fun a_daily_task_is_written_out_through_tomorrow_then_as_the_days_come() = runTest {
        store.addTask(task("Vitamins", repeat = Repeat.DAILY), today, through = tomorrow)

        assertEquals(listOf(today, tomorrow), days("Vitamins"))

        store.materialize(through = today.plusDays(3))
        store.materialize(through = today.plusDays(3))

        assertEquals((0L..3L).map { today.plusDays(it) }, days("Vitamins"))
    }

    @Test
    fun a_series_started_in_the_past_has_its_first_day_overdue_then_runs_from_today() = runTest {
        store.addTask(task("Rent", day = LocalDate.parse("2026-10-01"), repeat = Repeat.DAILY), today, through = tomorrow)

        assertEquals(listOf(LocalDate.parse("2026-10-01"), today, tomorrow), days("Rent"))
    }

    @Test
    fun an_answer_is_saved_with_its_time_as_the_last_nudge() = runTest {
        store.addTask(task("Call the bank"), today, through = tomorrow)
        val item = store.undone().single()
        val tap = Instant.parse("2026-10-29T09:05:00Z")

        store.saveStatus(item.id, Answers.inProgress(item.status, tap), answeredAt = tap)

        val saved = store.occurrence(item.id)!!
        assertEquals(OccurrenceState.IN_PROGRESS, saved.state)
        assertEquals(tap, saved.status.inProgressSince)
        assertEquals(tap, saved.lastNudgedAt)
    }

    @Test
    fun moving_a_one_off_task_moves_its_day_without_a_second_occurrence() = runTest {
        val taskId = store.addTask(task("Parking permit"), today, through = tomorrow)
        val item = store.undone().single()

        store.saveStatus(item.id, Answers.notToday(item.status, today), answeredAt = now)
        store.materialize(through = today.plusDays(7))

        assertEquals(listOf(tomorrow), days("Parking permit"))
        assertEquals(tomorrow, store.task(taskId)?.day)
        assertEquals(OccurrenceState.MOVED, store.undone().single().state)
    }

    @Test
    fun today_has_the_overdue_and_todays_tasks_done_or_not_but_nothing_ahead() = runTest {
        store.addTask(task("Overdue", day = today.minusDays(2)), today, through = tomorrow)
        store.addTask(task("Old and done", day = today.minusDays(1)), today, through = tomorrow)
        store.addTask(task("Today"), today, through = tomorrow)
        store.addTask(task("Done today"), today, through = tomorrow)
        store.addTask(task("Tomorrow", day = tomorrow), today, through = tomorrow)
        for (title in listOf("Old and done", "Done today")) {
            val item = store.undone().single { it.task.title == title }
            store.saveStatus(item.id, Answers.done(item.status, now), answeredAt = now)
        }

        val active = store.observeActive(today).first().map { it.task.title }

        assertEquals(listOf("Overdue", "Today", "Done today"), active)
    }

    @Test
    fun a_new_repeat_replans_from_today_but_keeps_answered_days() = runTest {
        val taskId = store.addTask(task("Plants", day = today.minusDays(3), repeat = Repeat.DAILY), today, through = tomorrow)
        val todays = store.undone().single { it.day == today }
        store.saveStatus(todays.id, Answers.inProgress(todays.status, now), answeredAt = now)
        val stored = store.task(taskId)!!

        val removed = store.updateTask(stored.copy(repeat = Repeat.WEEKLY), today, through = tomorrow, now = now)

        // Today's answered occurrence stays; tomorrow's untouched one goes (tomorrow isn't a weekly day).
        assertEquals(1, removed.size)
        assertEquals(listOf(today.minusDays(3), today), days("Plants"))
        assertEquals(now, store.undone().single { it.day == today.minusDays(3) }.lastNudgedAt)
    }

    @Test
    fun a_new_time_applies_to_every_open_occurrence_and_counts_as_seen() = runTest {
        val taskId = store.addTask(task("Vitamins", repeat = Repeat.DAILY), today, through = tomorrow)
        val stored = store.task(taskId)!!

        store.updateTask(stored.copy(startTime = LocalTime.of(8, 0)), today, through = tomorrow, now = now)

        assertTrue(store.undone().all { it.task.startTime == LocalTime.of(8, 0) && it.lastNudgedAt == now })
    }

    @Test
    fun deleting_a_task_deletes_its_occurrences() = runTest {
        val taskId = store.addTask(task("Vitamins", repeat = Repeat.DAILY), today, through = tomorrow)

        val removed = store.deleteTask(taskId)

        assertEquals(2, removed.size)
        assertTrue(store.undone().isEmpty())
        assertNull(store.task(taskId))
    }
}
