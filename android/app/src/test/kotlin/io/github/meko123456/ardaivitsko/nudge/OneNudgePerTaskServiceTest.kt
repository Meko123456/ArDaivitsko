package io.github.meko123456.ardaivitsko.nudge

import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.task
import io.github.meko123456.ardaivitsko.wallClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One nudge per task, through the real service: notifications, alarms and catch-up. */
class OneNudgePerTaskServiceTest {

    private suspend fun Harness.occurrences(): Map<LocalDate, OccurrenceItem> = store.undone().associateBy { it.originalDay }

    private fun days(from: Int, to: Int) = (from..to).map { day("2026-10-%02d".format(it)) }

    @Test
    fun `12 a daily task left for a week nudges only for its latest day, the rest stay quiet`() = runTest {
        val h = Harness(at("2026-10-21 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-22", repeat = Repeat.DAILY))

        h.advanceTo("2026-10-29 09:00") // the phone was off all week

        val byDay = h.occurrences()
        val quiet = days(22, 28).map { byDay.getValue(it).id }
        val latest = byDay.getValue(day("2026-10-29")).id
        assertTrue(h.notifier.posted.isEmpty(), "no catch-up for the quiet days")
        assertTrue(quiet.none { it in h.alarms.pending })
        assertEquals("2026-10-29 10:00 regular", h.nextAlarm(latest))

        for (time in listOf("10:00", "13:00", "16:00", "19:00")) h.advanceTo("2026-10-29 $time")

        assertEquals(setOf(latest), h.notifier.posted.map { it.occurrenceId }.toSet())
        assertEquals(listOf("10:00", "13:00", "16:00", "19:00"), h.notifier.posted.map { it.at.wallClock().substringAfter(' ') })
        // The quiet days are still listed for today, as overdue.
        assertEquals(days(22, 29), h.store.observeActive(day("2026-10-29")).first().map { it.originalDay })
    }

    @Test
    fun `12 done on the latest day leaves nothing nudging, and the earlier days stay listed`() = runTest {
        val h = Harness(at("2026-10-21 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-22", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-29 10:00")
        val byDay = h.occurrences()

        h.time.now = at("2026-10-29 10:05")
        h.service.answer(byDay.getValue(day("2026-10-29")).id, Answer.Done)
        h.advanceTo("2026-10-29 13:00")

        assertEquals(1, h.notifier.posted.size)
        assertTrue(days(22, 29).none { byDay.getValue(it).id in h.alarms.pending })
        assertEquals(7, h.store.observeActive(day("2026-10-29")).first().count { !it.isClosed })
    }

    @Test
    fun `a weekly task nudges for this week's day, and last week's stays quiet`() = runTest {
        val h = Harness(at("2026-10-18 09:00"))
        h.service.addTask(task(title = "Call mum", day = "2026-10-19", repeat = Repeat.WEEKLY)) // Mondays

        h.advanceTo("2026-10-29 09:00") // a Thursday

        val byDay = h.occurrences()
        assertNull(h.alarms.pending[byDay.getValue(day("2026-10-19")).id])
        assertEquals("2026-10-29 10:00 overdue", h.nextAlarm(byDay.getValue(day("2026-10-26")).id))
    }

    @Test
    fun `an older day in progress falls quiet once a later day comes`() = runTest {
        val h = Harness(at("2026-10-27 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-28", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-28 13:00")
        val older = h.occurrences().getValue(day("2026-10-28")).id
        h.time.now = at("2026-10-28 13:05")
        h.service.answer(older, Answer.InProgress)
        assertEquals("2026-10-28 19:05 regular", h.nextAlarm(older))

        h.advanceTo("2026-10-29 09:00")

        assertEquals(OccurrenceState.IN_PROGRESS, h.store.occurrence(older)?.state)
        assertNull(h.alarms.pending[older])
        assertEquals("2026-10-29 10:00 regular", h.nextAlarm(h.occurrences().getValue(day("2026-10-29")).id))
    }

    @Test
    fun `yesterday's nudge leaves the shade when today's day comes`() = runTest {
        val h = Harness(at("2026-10-27 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-28", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-28 19:00")
        val yesterday = h.occurrences().getValue(day("2026-10-28")).id
        assertTrue(yesterday in h.notifier.showing)

        h.advanceTo("2026-10-29 00:00") // the midnight tick

        assertFalse(yesterday in h.notifier.showing)
    }

    @Test
    fun `skip sets a missed day aside, and it leaves the list`() = runTest {
        val h = Harness(at("2026-10-26 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-27", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-29 11:00")
        val missed = h.occurrences().getValue(day("2026-10-27")).id

        val skipped = h.service.answer(missed, Answer.Skip)

        assertEquals(OccurrenceState.SKIPPED, skipped?.state)
        assertTrue(h.store.undone().none { it.id == missed })
        assertEquals(days(28, 29), h.store.observeActive(day("2026-10-29")).first().map { it.originalDay })
    }

    @Test
    fun `13 a day moved off today leaves nothing nudging, and tomorrow its own day nudges`() = runTest {
        val h = Harness(at("2026-10-27 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-28", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-29 11:00")
        val byDay = h.occurrences()
        val older = byDay.getValue(day("2026-10-28")).id
        val moved = byDay.getValue(day("2026-10-29")).id

        h.service.answer(moved, Answer.NotToday)

        assertNull(h.alarms.pending[older], "the older open day doesn't wake up")
        assertEquals("2026-10-30 10:00 regular", h.nextAlarm(moved))

        h.advanceTo("2026-10-30 10:00")

        val own = h.occurrences().getValue(day("2026-10-30")).id
        assertEquals(listOf(own), h.notifier.posted.filter { it.at >= at("2026-10-30 00:00") }.map { it.occurrenceId })
        assertNull(h.alarms.pending[moved], "the moved one sits quietly on the 30th")
        assertEquals(OccurrenceState.MOVED, h.store.occurrence(moved)?.state)
    }
}
