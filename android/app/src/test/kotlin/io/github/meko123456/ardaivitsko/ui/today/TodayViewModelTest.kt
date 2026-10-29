package io.github.meko123456.ardaivitsko.ui.today

import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.MainDispatcherRule
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.keepCollecting
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.record
import io.github.meko123456.ardaivitsko.task
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class TodayViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private fun Harness.viewModel() = TodayViewModel(store, service, settings, time, formats)

    @Test
    fun `overdue comes first, then today's open tasks, then the done ones`() = runTest {
        val h = Harness(at("2026-10-28 09:00"))
        h.add(task(title = "Pay the credit card bill", day = "2026-10-28", cantMiss = true))
        h.time.now = at("2026-10-29 11:30")
        h.add(task(title = "Buy medicine", day = "2026-10-29"))
        val call = h.add(task(title = "Call the bank", day = "2026-10-29", deadline = "18:00"))
        val done = h.add(task(title = "Water the plants", day = "2026-10-29", deadline = "09:00"))
        h.service.answer(done, Answer.Done)

        val vm = h.viewModel()
        keepCollecting(vm.state)
        val state = vm.state.value

        assertEquals("Thursday, 29 October", state.date)
        assertEquals(listOf("Pay the credit card bill"), state.overdue.map { it.title })
        assertEquals("Overdue since 28 Oct", state.overdue.single().dueLine)
        assertEquals(listOf("Call the bank", "Buy medicine", "Water the plants"), state.today.map { it.title })
        assertEquals("Due today by 18:00", state.today.first { it.occurrenceId == call }.dueLine)
        assertEquals("Open · Next nudge at 13:00", state.today.first { it.occurrenceId == call }.statusLine)
        assertEquals("Done", state.today.last().statusLine)
    }

    @Test
    fun `done from the list says so, and undo opens it again`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val id = h.add(task(title = "Pay rent"))
        val vm = h.viewModel()
        keepCollecting(vm.state)
        val events = record(vm.eventFlow)

        vm.markDone(vm.state.value.today.single())

        assertEquals(listOf<TodayEvent>(TodayEvent.MarkedDone(id, "Pay rent")), events)
        assertEquals(OccurrenceState.DONE, vm.state.value.today.single().state)
        assertEquals(null, h.alarms.pending[id])

        vm.reopen(id)

        assertEquals(OccurrenceState.OPEN, vm.state.value.today.single().state)
        assertEquals("Open · Next nudge at 13:00", vm.state.value.today.single().statusLine)
    }

    @Test
    fun `a moved task shows where it came from`() = runTest {
        val h = Harness(at("2026-10-28 11:00"))
        val id = h.add(task(title = "Renew the parking permit", day = "2026-10-28"))
        h.service.answer(id, Answer.NotToday)
        h.time.now = at("2026-10-29 09:00")

        val vm = h.viewModel()
        keepCollecting(vm.state)

        assertEquals("Moved from 28 Oct · Next nudge at 10:00", vm.state.value.today.single().statusLine)
        assertEquals(emptyList(), vm.state.value.overdue)
    }

    @Test
    fun `in progress shows with its slower next nudge`() = runTest {
        val h = Harness(at("2026-10-29 13:05"))
        val id = h.add(task(title = "Call the bank"))
        h.service.answer(id, Answer.InProgress)

        val vm = h.viewModel()
        keepCollecting(vm.state)

        assertEquals("In progress · Next nudge at 19:05", vm.state.value.today.single().statusLine)
    }
}
