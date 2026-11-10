package io.github.meko123456.ardaivitsko.calendar

import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.data.CalendarSettings
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.task
import kotlinx.coroutines.test.runTest
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Google Calendar channel over a fake calendar. Today is Tuesday 10 November 2026, Tbilisi. */
class CalendarSyncTest {

    private val calendarId = 7L

    private fun harness(watch: Boolean = true, write: Boolean = false): Harness {
        val calendar = CalendarSettings(
            writeCalendarId = calendarId.takeIf { write },
            watchedCalendarIds = if (watch) setOf(calendarId) else emptySet(),
        )
        return Harness(at("2026-11-10 09:00"), AppSettings(calendar = calendar)).apply {
            provider.calendars += CalendarInfo(calendarId, "Ar Daivitsko", "merab@example.com", writable = true)
        }
    }

    @Test
    fun `an event in a watched calendar becomes a task, once`() = runTest {
        val h = harness()
        h.provider.put(calendarId, "Dentist", day("2026-11-11"), LocalTime.of(15, 30), note = "Bring the card")

        h.calendar.syncNow()
        h.calendar.syncNow()

        val task = h.store.undone().single().task
        assertEquals("Dentist", task.title)
        assertEquals("Bring the card", task.note)
        assertEquals(day("2026-11-11"), task.day)
        assertEquals(LocalTime.of(15, 30), task.deadline)
        assertEquals(Repeat.NEVER, task.repeat)
        assertEquals(1, h.links.links.size)
        assertEquals(true, h.periodic)
    }

    @Test
    fun `an all-day event is due by the day's end, and takes the defaults for its nudges`() = runTest {
        val h = harness()
        h.settings.state.value = h.settings.state.value.copy(defaults = TaskDefaults(startTime = LocalTime.of(9, 0), intervalHours = 2))
        h.provider.put(calendarId, "Renew the parking permit", day("2026-11-12"))

        h.calendar.syncNow()

        val task = h.store.undone().single().task
        assertEquals(day("2026-11-12"), task.day)
        assertEquals(LocalTime.of(23, 59), task.deadline)
        assertEquals(LocalTime.of(9, 0), task.startTime)
        assertEquals(2, task.intervalHours)
    }

    @Test
    fun `a weekly event from long ago starts here on its next day`() = runTest {
        val h = harness()
        h.provider.put(calendarId, "Team lunch", day("2024-01-01"), LocalTime.of(12, 0), rrule = "FREQ=WEEKLY;BYDAY=MO")

        h.calendar.syncNow()

        val task = h.store.undone().first().task
        assertEquals(Repeat.WEEKLY, task.repeat)
        assertEquals(day("2026-11-16"), task.day) // the Monday after Tuesday the 10th
        assertEquals(LocalTime.of(12, 0), task.deadline)
    }

    @Test
    fun `every second week comes once, on its first day`() = runTest {
        val h = harness()
        h.provider.put(calendarId, "Bins", day("2026-11-13"), rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=FR")

        h.calendar.syncNow()

        val task = h.store.undone().single().task
        assertEquals(Repeat.NEVER, task.repeat)
        assertEquals(day("2026-11-13"), task.day)
    }

    @Test
    fun `done puts a tick in front of the event, and mark as not done takes it off`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Dentist", day("2026-11-11"), LocalTime.of(15, 30))
        h.calendar.syncNow()
        val item = h.store.undone().single()

        h.service.answer(item.id, Answer.Done)
        h.calendar.syncNow()
        assertEquals("✓ Dentist", h.provider.events.getValue(eventId).title)

        h.service.answer(item.id, Answer.Reopen)
        h.calendar.syncNow()
        assertEquals("Dentist", h.provider.events.getValue(eventId).title)
    }

    @Test
    fun `not today moves the event, keeping it all-day`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Parking permit", day("2026-11-10"))
        h.calendar.syncNow()
        val item = h.store.undone().single()

        h.service.answer(item.id, Answer.NotToday)
        h.calendar.syncNow()

        val event = h.provider.events.getValue(eventId)
        assertTrue(event.allDay)
        assertEquals(day("2026-11-11"), event.start.atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(listOf(eventId), h.provider.updates)

        h.service.answer(item.id, Answer.UndoMove)
        h.calendar.syncNow()
        assertEquals(day("2026-11-10"), h.provider.events.getValue(eventId).start.atZone(ZoneOffset.UTC).toLocalDate())
    }

    @Test
    fun `an event moved and renamed in the calendar moves and renames the task`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Dentist", day("2026-11-11"), LocalTime.of(15, 30))
        h.calendar.syncNow()
        val before = h.store.undone().single()

        h.provider.events[eventId] = h.provider.events.getValue(eventId).copy(
            title = "Dentist, Dr Beridze",
            start = day("2026-11-13").atTime(16, 0).atZone(h.time.zone).toInstant(),
        )
        h.calendar.syncNow()

        val after = h.store.undone().single()
        assertEquals(before.id, after.id) // the one occurrence moved, as an edit moves it
        assertEquals("Dentist, Dr Beridze", after.task.title)
        assertEquals(day("2026-11-13"), after.day)
        assertEquals(LocalTime.of(16, 0), after.task.deadline)
        assertTrue(h.provider.updates.isEmpty()) // nothing written back for a change that came from there
    }

    @Test
    fun `the tick is the app's, and taken off in the calendar it comes back`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Dentist", day("2026-11-11"))
        h.calendar.syncNow()
        h.service.answer(h.store.undone().single().id, Answer.Done)
        h.calendar.syncNow()

        h.provider.events[eventId] = h.provider.events.getValue(eventId).copy(title = "Dentist")
        h.calendar.syncNow()

        assertEquals("✓ Dentist", h.provider.events.getValue(eventId).title)
        assertEquals(OccurrenceState.DONE, h.store.occurrencesOf(h.links.links.keys.single()).single().state)
    }

    @Test
    fun `a deleted event leaves the task, without its link`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Dentist", day("2026-11-11"))
        h.calendar.syncNow()

        h.provider.events.remove(eventId)
        h.calendar.syncNow()

        assertEquals("Dentist", h.store.undone().single().task.title)
        assertTrue(h.links.links.isEmpty())
    }

    @Test
    fun `a spoken task goes to the calendar as an all-day event, and nothing is written again`() = runTest {
        val h = harness(watch = false, write = true)
        val id = h.add(task("Renew the parking permit", day = "2026-11-12"))

        assertTrue(h.calendar.addEvent(id, calendarId))
        h.calendar.syncNow()

        val event = h.provider.events.values.single()
        assertEquals("Renew the parking permit", event.title)
        assertTrue(event.allDay)
        assertEquals(day("2026-11-12"), event.start.atZone(ZoneOffset.UTC).toLocalDate())
        assertTrue(h.provider.updates.isEmpty())
        assertEquals(id, h.links.links.keys.single())
    }

    @Test
    fun `a spoken task with a time is an event at that time, and a weekly one repeats`() = runTest {
        val h = harness(watch = false, write = true)
        val id = h.add(task("Team lunch", day = "2026-11-16", deadline = "12:00", repeat = Repeat.WEEKLY))

        h.calendar.addEvent(id, calendarId)

        val event = h.provider.events.values.single()
        assertFalse(event.allDay)
        assertEquals(day("2026-11-16").atTime(12, 0), event.start.atZone(h.time.zone).toLocalDateTime())
        assertEquals("FREQ=WEEKLY;BYDAY=MO", event.rrule)
    }

    @Test
    fun `a written task in a watched calendar isn't imported again`() = runTest {
        val h = harness(watch = true, write = true)
        val id = h.add(task("Renew the parking permit", day = "2026-11-12"))
        h.calendar.addEvent(id, calendarId)

        h.calendar.syncNow()

        assertEquals(1, h.store.undone().size)
    }

    @Test
    fun `a ticked event in a watched calendar is left alone`() = runTest {
        val h = harness()
        h.provider.put(calendarId, "✓ Old dentist", day("2026-11-11"))

        h.calendar.syncNow()

        assertTrue(h.store.undone().isEmpty())
    }

    @Test
    fun `a repeating task and its event share only the title`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Team lunch", day("2026-11-09"), LocalTime.of(12, 0), rrule = "FREQ=WEEKLY;BYDAY=MO")
        h.calendar.syncNow()
        val taskId = h.links.links.keys.single()

        h.service.answer(h.store.undone().first { it.task.id == taskId }.id, Answer.NotToday)
        h.calendar.syncNow()
        assertTrue(h.provider.updates.isEmpty())

        h.service.updateTask(h.store.task(taskId)!!.copy(title = "Team lunch at Keto's"))
        h.calendar.syncNow()
        assertEquals("Team lunch at Keto's", h.provider.events.getValue(eventId).title)
        assertEquals(day("2026-11-09").atTime(12, 0), h.provider.events.getValue(eventId).start.atZone(h.time.zone).toLocalDateTime())
    }

    @Test
    fun `without calendar access nothing is read or written, and the background check is off`() = runTest {
        val h = harness()
        h.provider.access = false
        h.provider.put(calendarId, "Dentist", day("2026-11-11"))

        h.calendar.syncNow()

        assertTrue(h.store.undone().isEmpty())
        assertEquals(true, h.periodic) // still wanted: it's the permission that's missing
        h.settings.state.value = h.settings.state.value.copy(calendar = CalendarSettings())
        h.calendar.syncNow()
        assertEquals(false, h.periodic)
    }

    @Test
    fun `the task wins when both sides changed`() = runTest {
        val h = harness()
        val eventId = h.provider.put(calendarId, "Dentist", day("2026-11-10"))
        h.calendar.syncNow()
        val item = h.store.undone().single()

        // Renamed in the calendar, and moved on in the app, since they last agreed.
        h.provider.events[eventId] = h.provider.events.getValue(eventId).copy(title = "Dentist, Dr Beridze")
        h.service.answer(item.id, Answer.NotToday)
        h.calendar.syncNow()

        val event = h.provider.events.getValue(eventId)
        assertEquals("Dentist", event.title)
        assertEquals(day("2026-11-11"), event.start.atZone(ZoneOffset.UTC).toLocalDate())
        assertNull(h.store.undone().firstOrNull { it.task.title != "Dentist" })
    }

    @Test
    fun `after a restore the links go, and a watched calendar's events are tasks again only if new`() = runTest {
        val h = harness()
        h.provider.put(calendarId, "Dentist", day("2026-11-11"))
        h.calendar.syncNow()

        h.calendar.clearLinks()
        h.calendar.syncNow()

        // The event is linked again, to a second task: the restored tasks can't be told from new ones.
        assertEquals(2, h.store.undone().size)
        assertEquals(1, h.links.links.size)
    }
}
