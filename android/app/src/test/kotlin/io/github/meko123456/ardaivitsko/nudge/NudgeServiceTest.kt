package io.github.meko123456.ardaivitsko.nudge

import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.NudgeKind
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.QuietHours
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.task
import io.github.meko123456.ardaivitsko.wallClock
import kotlinx.coroutines.test.runTest
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NudgeServiceTest {

    @Test
    fun `a new task is created now and waits for its first grid time`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))

        val id = h.add(task())

        assertEquals(at("2026-10-29 09:00"), h.store.occurrence(id)?.task?.createdAt)
        assertEquals("2026-10-29 10:00 regular", h.nextAlarm(id))
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun `each alarm posts its nudge and sets the next`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())

        h.advanceTo("2026-10-29 10:00")

        assertEquals(NudgeKind.REGULAR, h.notifier.showing[id])
        assertEquals("2026-10-29 13:00 regular", h.nextAlarm(id))
    }

    @Test
    fun `done takes the notification and the alarm away for good`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 10:00")

        h.time.now = at("2026-10-29 10:05")
        h.service.answer(id, Answer.Done)
        h.advanceTo("2026-10-29 13:00")
        h.advanceTo("2026-10-30 10:00")

        assertNull(h.notifier.showing[id])
        assertNull(h.alarms.pending[id])
        assertEquals(1, h.notifier.posted.size)
    }

    @Test
    fun `in progress sets the next alarm twice the interval after the tap`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 13:00")

        h.time.now = at("2026-10-29 13:05")
        h.service.answer(id, Answer.InProgress)

        assertNull(h.notifier.showing[id])
        assertEquals("2026-10-29 19:05 regular", h.nextAlarm(id))
    }

    @Test
    fun `not today moves it to tomorrow, and undo brings it back`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 10:00")

        h.time.now = at("2026-10-29 11:00")
        val moved = h.service.answer(id, Answer.NotToday)

        assertEquals(OccurrenceState.MOVED, moved?.state)
        assertEquals(day("2026-10-30"), moved?.day)
        assertEquals("2026-10-30 10:00 regular", h.nextAlarm(id))

        val back = h.service.answer(id, Answer.UndoMove)

        assertEquals(OccurrenceState.OPEN, back?.state)
        assertEquals(day("2026-10-29"), back?.day)
        assertEquals("2026-10-29 13:00 regular", h.nextAlarm(id))
        assertTrue(h.notifier.posted.size == 1, "no catch-up after the undo")
    }

    @Test
    fun `snooze sets the next alarm an hour after the tap`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 13:00")

        h.time.now = at("2026-10-29 13:02")
        h.service.answer(id, Answer.Snooze)

        assertEquals("2026-10-29 14:02 regular", h.nextAlarm(id))
    }

    @Test
    fun `a phone off all afternoon gets one catch-up, the latest missed`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 10:00")

        h.advanceTo("2026-10-29 20:30") // boot, after 13:00, 16:00 and 19:00 went by

        assertEquals(2, h.notifier.posted.size)
        assertEquals(at("2026-10-29 19:00"), h.notifier.posted.last().at)
        assertEquals("2026-10-30 10:00 overdue", h.nextAlarm(id))
    }

    @Test
    fun `a can't-miss task sets its full-screen alarm for 23 00`() = runTest {
        val h = Harness(at("2026-10-28 09:00"))
        val id = h.add(task(day = "2026-10-28", cantMiss = true))

        h.advanceTo("2026-10-28 22:30")

        assertEquals(NudgeKind.FINAL, h.notifier.showing[id])
        assertEquals("2026-10-28 23:00 alarm", h.nextAlarm(id))
    }

    @Test
    fun `an unanswered task carries over to the next day as overdue`() = runTest {
        val h = Harness(at("2026-10-28 09:00"))
        val id = h.add(task(day = "2026-10-28", cantMiss = true))
        h.advanceTo("2026-10-28 23:59")

        assertEquals(NudgeKind.DEADLINE_PASSED, h.notifier.showing[id])
        assertEquals("2026-10-29 10:00 overdue", h.nextAlarm(id))

        h.advanceTo("2026-10-29 10:00")

        assertEquals(NudgeKind.OVERDUE, h.notifier.showing[id])
    }

    @Test
    fun `a daily task gets tomorrow's occurrence a day ahead and both nudge`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        h.add(task(title = "Vitamins", repeat = Repeat.DAILY))

        assertEquals(listOf(day("2026-10-29"), day("2026-10-30")), h.store.undone().map { it.day })

        h.advanceTo("2026-10-30 10:00")

        val kinds = h.store.undone().filter { it.day <= day("2026-10-30") }.associate { it.day to h.notifier.showing[it.id] }
        assertEquals(mapOf(day("2026-10-29") to NudgeKind.OVERDUE, day("2026-10-30") to NudgeKind.REGULAR), kinds)
        assertTrue(h.store.undone().any { it.day == day("2026-10-31") })
    }

    @Test
    fun `deleting a task clears its notification and alarm`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 10:00")

        h.service.deleteTask(h.store.occurrence(id)!!.task.id)

        assertNull(h.notifier.showing[id])
        assertNull(h.alarms.pending[id])
    }

    @Test
    fun `an answer to a deleted task only clears it away`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))

        assertNull(h.service.answer(occurrenceId = 99, answer = Answer.Done))
    }

    @Test
    fun `turning quiet hours off doesn't fire what they held back`() = runTest {
        val h = Harness(at("2026-10-29 21:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 23:00")
        val before = h.notifier.posted.size

        h.settings.update { it.copy(quietHoursOn = false) }
        h.service.settingsChanged()

        assertEquals(before, h.notifier.posted.size)
        assertEquals("2026-10-30 10:00 overdue", h.nextAlarm(id))
    }

    @Test
    fun `an earlier start time after an edit doesn't fire a catch-up`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task(start = "12:00"))
        h.time.now = at("2026-10-29 11:00")

        val stored = h.store.occurrence(id)!!.task
        h.service.updateTask(stored.copy(startTime = LocalTime.of(8, 0)))

        assertTrue(h.notifier.posted.isEmpty())
        assertEquals("2026-10-29 14:00 regular", h.nextAlarm(id))
    }

    @Test
    fun `a new time zone keeps 10 00 on the wall clock`() = runTest {
        val h = Harness(at("2026-10-29 07:00"))
        val id = h.add(task(day = "2026-10-30"))
        val tokyo = ZoneId.of("Asia/Tokyo")

        h.time.zone = tokyo
        h.service.reconcile()

        assertEquals("2026-10-30 10:00", h.alarms.pending.getValue(id).at.wallClock(tokyo))
    }

    @Test
    fun `quiet hours set in settings move the next nudge`() = runTest {
        val h = Harness(at("2026-10-29 09:00"), AppSettings(quietHours = QuietHours(LocalTime.of(9, 0), LocalTime.of(14, 0))))
        val id = h.add(task())

        assertEquals("2026-10-29 16:00 regular", h.nextAlarm(id))
    }

    @Test
    fun `the day tick is set for the coming midnight and every change refreshes the widget`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        h.add(task())

        assertEquals("2026-10-30 00:00", h.alarms.tickAt?.wallClock())
        assertTrue(h.widgetRefreshes > 0)
    }
}
