package io.github.meko123456.ardaivitsko.ui.quickadd

import androidx.lifecycle.SavedStateHandle
import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.MainDispatcherRule
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.calendar.CalendarInfo
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.data.CalendarSettings
import io.github.meko123456.ardaivitsko.day
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Say or type a task. Today is Tuesday 10 November 2026. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private fun Harness.viewModel(handle: SavedStateHandle = SavedStateHandle(), listen: Boolean = false) =
        QuickAddViewModel(handle, listen, service, settings, time, calendar, provider, io = UnconfinedTestDispatcher())

    @Test
    fun `a typed sentence is read as it's typed`() = runTest {
        val h = Harness(at("2026-11-10 09:00"))
        val vm = h.viewModel()

        vm.setSentence("renew the parking permit on Thursday at 10")

        val state = vm.state
        assertEquals("Renew the parking permit", state.title)
        assertEquals(day("2026-11-12"), state.day)
        assertEquals(LocalTime.of(10, 0), state.time)
        assertEquals(Repeat.NEVER, state.repeat)
        assertTrue(state.canAdd)
        assertNull(state.calendarName)
    }

    @Test
    fun `a day picked by hand holds against the sentence, until the mic speaks again`() = runTest {
        val h = Harness(at("2026-11-10 09:00"))
        val vm = h.viewModel()
        vm.setSentence("call the bank tomorrow")

        vm.pickDay(day("2026-11-20"))
        vm.setSentence("call the bank tomorrow at 3")

        assertEquals(day("2026-11-20"), vm.state.day)
        assertEquals(LocalTime.of(15, 0), vm.state.time)

        vm.heard("call the bank on Friday")

        assertEquals(day("2026-11-13"), vm.state.day)
        assertNull(vm.state.time)
    }

    @Test
    fun `adding makes the task with the defaults for what the sentence didn't say`() = runTest {
        val defaults = TaskDefaults(deadline = LocalTime.of(18, 0), startTime = LocalTime.of(9, 0), intervalHours = 2)
        val h = Harness(at("2026-11-10 09:00"), AppSettings(defaults = defaults))
        val vm = h.viewModel()
        vm.setSentence("every Monday take out the bins")

        vm.add()

        val added = vm.eventFlow.first() as QuickAddEvent.Added
        assertEquals("Take out the bins", added.title)
        assertNull(added.inCalendar)
        val task = h.store.undone().first().task
        assertEquals(day("2026-11-16"), task.day)
        assertEquals(Repeat.WEEKLY, task.repeat)
        assertEquals(LocalTime.of(18, 0), task.deadline)
        assertEquals(LocalTime.of(9, 0), task.startTime)
        assertEquals(2, task.intervalHours)
    }

    @Test
    fun `with a calendar chosen in settings the task also goes there, unless switched off`() = runTest {
        val h = Harness(at("2026-11-10 09:00"), AppSettings(calendar = CalendarSettings(writeCalendarId = 7)))
        h.provider.calendars += CalendarInfo(7, "Ar Daivitsko", "merab@example.com", writable = true)
        val vm = h.viewModel()
        assertEquals("Ar Daivitsko", vm.state.calendarName)
        assertTrue(vm.state.toCalendar)
        vm.setSentence("dentist on Thursday at 15:30")

        vm.add()

        assertEquals(true, (vm.eventFlow.first() as QuickAddEvent.Added).inCalendar)
        val event = h.provider.events.values.single()
        assertEquals("Dentist", event.title)
        assertEquals(day("2026-11-12").atTime(15, 30), event.start.atZone(h.time.zone).toLocalDateTime())

        val second = h.viewModel()
        second.setToCalendar(false)
        second.setSentence("buy milk")
        second.add()

        assertNull((second.eventFlow.first() as QuickAddEvent.Added).inCalendar)
        assertEquals(1, h.provider.events.size)
    }

    @Test
    fun `a sentence that's only a day can't be added, and the state survives a new handle`() = runTest {
        val h = Harness(at("2026-11-10 09:00"))
        val handle = SavedStateHandle()
        val vm = h.viewModel(handle)

        vm.setSentence("tomorrow")
        assertFalse(vm.state.canAdd)

        vm.setSentence("water the plants tomorrow")
        vm.pickTime(LocalTime.of(8, 0))
        val restored = h.viewModel(handle)

        assertEquals("Water the plants", restored.state.title)
        assertEquals(day("2026-11-11"), restored.state.day)
        assertEquals(LocalTime.of(8, 0), restored.state.time)
    }

    @Test
    fun `the shortcut's listen request is taken once`() = runTest {
        val h = Harness(at("2026-11-10 09:00"))
        val handle = SavedStateHandle()
        val vm = h.viewModel(handle, listen = true)

        assertTrue(vm.takeListenRequest())
        assertFalse(vm.takeListenRequest())
        assertFalse(h.viewModel(handle, listen = true).takeListenRequest()) // the same screen, after a rotation
    }
}
