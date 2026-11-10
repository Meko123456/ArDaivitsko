package io.github.meko123456.ardaivitsko.calendar

import io.github.meko123456.ardaivitsko.TBILISI
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.task
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventMappingTest {

    @Test
    fun `the repeats the app has, and only those`() {
        assertEquals(Repeat.NEVER, EventMapping.repeatOf(null))
        assertEquals(Repeat.DAILY, EventMapping.repeatOf("FREQ=DAILY"))
        assertEquals(Repeat.WEEKLY, EventMapping.repeatOf("FREQ=WEEKLY;WKST=MO;BYDAY=TH"))
        assertEquals(Repeat.MONTHLY, EventMapping.repeatOf("FREQ=MONTHLY;BYMONTHDAY=15"))
        assertEquals(Repeat.MONTHLY, EventMapping.repeatOf("freq=monthly"))
        assertEquals(Repeat.NEVER, EventMapping.repeatOf("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR"))
        assertEquals(Repeat.NEVER, EventMapping.repeatOf("FREQ=WEEKLY;BYDAY=MO,WE,FR"))
        assertEquals(Repeat.NEVER, EventMapping.repeatOf("FREQ=MONTHLY;BYDAY=1MO"))
        assertEquals(Repeat.NEVER, EventMapping.repeatOf("FREQ=YEARLY"))
    }

    @Test
    fun `a task's rule names its weekday or day of the month`() {
        assertNull(EventMapping.rrule(Repeat.NEVER, day("2026-11-12")))
        assertEquals("FREQ=DAILY", EventMapping.rrule(Repeat.DAILY, day("2026-11-12")))
        assertEquals("FREQ=WEEKLY;BYDAY=TH", EventMapping.rrule(Repeat.WEEKLY, day("2026-11-12")))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=31", EventMapping.rrule(Repeat.MONTHLY, day("2026-10-31")))
    }

    @Test
    fun `an all-day event's day is its UTC date, whatever the phone's zone`() {
        val event = CalendarEvent(1, 1, "Rent", "", day("2026-11-12").atStartOfDay(ZoneOffset.UTC).toInstant(), allDay = true, rrule = null)

        val side = EventMapping.eventSide(event, TBILISI)

        assertEquals(SyncedEvent("Rent", day("2026-11-12"), null, done = false), side)
    }

    @Test
    fun `a timed event is read in the phone's zone, and a tick is read as done`() {
        val event = CalendarEvent(1, 1, "✓ Dentist", "", Instant.parse("2026-11-11T11:30:00Z"), allDay = false, rrule = null)

        val side = EventMapping.eventSide(event, TBILISI)

        assertEquals(SyncedEvent("Dentist", day("2026-11-11"), LocalTime.of(15, 30), done = true), side)
    }

    @Test
    fun `a task is all-day when its deadline is the day's end, else timed`() {
        assertNull(EventMapping.taskSide(task(deadline = "23:59"), day("2026-11-12"), done = false).time)
        assertEquals(LocalTime.of(18, 0), EventMapping.taskSide(task(deadline = "18:00"), day("2026-11-12"), done = false).time)
        assertEquals("✓ Pay the credit card bill", EventMapping.eventDraft(task(), day("2026-11-12"), done = true).title)
    }

    @Test
    fun `a monthly event from the 31st comes back on its next day from today, clamped`() {
        val event = CalendarEvent(1, 1, "Rent", "", day("2026-01-31").atStartOfDay(ZoneOffset.UTC).toInstant(), allDay = true, rrule = "FREQ=MONTHLY;BYMONTHDAY=31")

        val task = EventMapping.importedTask(event, today = day("2026-11-10"), zone = TBILISI, defaults = TaskDefaults(), now = Instant.EPOCH)!!

        assertEquals(day("2026-11-30"), task.day)
        assertEquals(Repeat.MONTHLY, task.repeat)
    }

    @Test
    fun `an event with no title is no task`() {
        val event = CalendarEvent(1, 1, "  ", "", Instant.EPOCH, allDay = true, rrule = null)

        assertNull(EventMapping.importedTask(event, day("2026-11-10"), TBILISI, TaskDefaults(), Instant.EPOCH))
    }
}
