package io.github.meko123456.ardaivitsko.ui.task

import androidx.lifecycle.SavedStateHandle
import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.MainDispatcherRule
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.keepCollecting
import io.github.meko123456.ardaivitsko.record
import io.github.meko123456.ardaivitsko.task
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private fun Harness.viewModel(id: Long, confirmMove: Boolean = false, handle: SavedStateHandle = SavedStateHandle()) =
        TaskViewModel(id, confirmMove, handle, store, service, settings, time, formats)

    @Test
    fun `not today on a can't-miss task's last day asks first`() = runTest {
        val h = Harness(at("2026-10-28 12:00"))
        val id = h.add(task(day = "2026-10-28", cantMiss = true))
        val vm = h.viewModel(id)
        keepCollecting(vm.state)
        val events = record(vm.eventFlow)

        vm.notToday()

        assertTrue(vm.state.value.confirmingMove)
        assertEquals(day("2026-10-28"), h.store.occurrence(id)?.day)

        vm.confirmMove()

        assertFalse(vm.state.value.confirmingMove)
        assertEquals(day("2026-10-29"), h.store.occurrence(id)?.day)
        assertEquals(listOf<TaskEvent>(TaskEvent.Message("Moved to tomorrow", undoMove = true)), events)
    }

    @Test
    fun `keeping it today moves nothing`() = runTest {
        val h = Harness(at("2026-10-28 12:00"))
        val id = h.add(task(day = "2026-10-28", cantMiss = true))
        val vm = h.viewModel(id)
        keepCollecting(vm.state)

        vm.notToday()
        vm.dismissConfirm()

        assertFalse(vm.state.value.confirmingMove)
        assertEquals(OccurrenceState.OPEN, h.store.occurrence(id)?.state)
    }

    @Test
    fun `not today on an ordinary task moves at once, and undo puts it back`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val id = h.add(task(title = "Renew the parking permit"))
        val vm = h.viewModel(id)
        keepCollecting(vm.state)

        vm.notToday()

        assertEquals("Moved from 29 Oct", vm.state.value.status)
        assertEquals("Due tomorrow by 23:59", vm.state.value.dueLine)
        assertTrue(vm.state.value.canUndoMove)

        vm.undoMove()

        assertEquals("Open", vm.state.value.status)
        assertEquals(day("2026-10-29"), vm.state.value.day)
    }

    @Test
    fun `opened from the notification's not today it asks once, not again after a rotation`() = runTest {
        val h = Harness(at("2026-10-28 12:00"))
        val id = h.add(task(day = "2026-10-28", cantMiss = true))
        val handle = SavedStateHandle()
        val first = h.viewModel(id, confirmMove = true, handle = handle)
        keepCollecting(first.state)

        assertTrue(first.state.value.confirmingMove)
        first.dismissConfirm()

        val recreated = h.viewModel(id, confirmMove = true, handle = handle)
        keepCollecting(recreated.state)

        assertFalse(recreated.state.value.confirmingMove)
    }

    @Test
    fun `snooze says when the next nudge comes`() = runTest {
        val h = Harness(at("2026-10-29 13:02"))
        val id = h.add(task(title = "Pay rent"))
        val vm = h.viewModel(id)
        keepCollecting(vm.state)
        val events = record(vm.eventFlow)

        vm.snooze()

        assertEquals(listOf<TaskEvent>(TaskEvent.Message("Snoozed. Next nudge at 14:02")), events)
        assertEquals("Next nudge at 14:02", vm.state.value.nextNudge)
    }

    @Test
    fun `snooze and not today aren't offered for a task still ahead`() = runTest {
        val h = Harness(at("2026-10-27 19:00"))
        val id = h.add(task(day = "2026-10-28", cantMiss = true))
        val vm = h.viewModel(id)
        keepCollecting(vm.state)

        assertFalse(vm.state.value.canSnooze)
        assertFalse(vm.state.value.canMoveToTomorrow)
    }

    @Test
    fun `an earlier quiet day offers done and skip, and skip sets it aside`() = runTest {
        val h = Harness(at("2026-10-26 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-27", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-29 11:00")
        val missed = h.store.undone().single { it.originalDay == day("2026-10-27") }.id
        val vm = h.viewModel(missed)
        keepCollecting(vm.state)

        assertTrue(vm.state.value.quiet)
        assertFalse(vm.state.value.canSnooze)
        assertFalse(vm.state.value.canMoveToTomorrow)
        assertEquals("No nudges: a later day of this task nudges instead", vm.state.value.nextNudge)

        vm.skip()

        assertEquals("Skipped", vm.state.value.status)
        assertTrue(vm.state.value.done)
        assertEquals(OccurrenceState.SKIPPED, h.store.occurrence(missed)?.state)
    }

    @Test
    fun `the latest day of a repeating task isn't quiet`() = runTest {
        val h = Harness(at("2026-10-26 09:00"))
        h.service.addTask(task(title = "Water the plants", day = "2026-10-27", repeat = Repeat.DAILY))
        h.advanceTo("2026-10-29 11:00")
        val latest = h.store.undone().single { it.originalDay == day("2026-10-29") }.id
        val vm = h.viewModel(latest)
        keepCollecting(vm.state)

        assertFalse(vm.state.value.quiet)
        assertTrue(vm.state.value.canSnooze)
        assertEquals("Next nudge at 13:00", vm.state.value.nextNudge)
    }

    @Test
    fun `delete removes the task and closes the screen`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val id = h.add(task())
        val vm = h.viewModel(id)
        keepCollecting(vm.state)
        val events = record(vm.eventFlow)

        vm.delete()

        assertEquals(listOf<TaskEvent>(TaskEvent.Deleted), events)
        assertTrue(vm.state.value.missing)
    }
}
