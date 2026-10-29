package io.github.meko123456.ardaivitsko.ui.edit

import androidx.lifecycle.SavedStateHandle
import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.MainDispatcherRule
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.record
import io.github.meko123456.ardaivitsko.task
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditTaskViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private fun Harness.viewModel(taskId: Long = 0, handle: SavedStateHandle = SavedStateHandle(), initialDay: String? = null) =
        EditTaskViewModel(taskId, initialDay?.let(::day), handle, store, service, settings, time)

    @Test
    fun `a new task starts from the defaults in settings, on today`() = runTest {
        val defaults = TaskDefaults(deadline = LocalTime.of(18, 0), startTime = LocalTime.of(9, 0), intervalHours = 2)
        val h = Harness(at("2026-10-29 11:00"), AppSettings(defaults = defaults))

        val form = h.viewModel().form!!

        assertEquals(day("2026-10-29"), form.day)
        assertEquals(LocalTime.of(18, 0), form.deadline)
        assertEquals(LocalTime.of(9, 0), form.startTime)
        assertEquals(2, form.intervalHours)
        assertEquals(Repeat.NEVER, form.repeat)
    }

    @Test
    fun `a new task from the calendar starts on the chosen day`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))

        assertEquals(day("2026-11-05"), h.viewModel(initialDay = "2026-11-05").form?.day)
    }

    @Test
    fun `a blank title can't be saved`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val vm = h.viewModel()

        vm.update { it.copy(title = "   ") }
        vm.save()

        assertFalse(vm.form!!.canSave)
        assertTrue(h.store.undone().isEmpty())
    }

    @Test
    fun `saving adds the task and schedules it`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val vm = h.viewModel()
        val events = record(vm.eventFlow)

        vm.update { it.copy(title = "  Pay the credit card bill ", cantMiss = true) }
        vm.save()

        val added = h.store.undone().single()
        assertEquals("Pay the credit card bill", added.task.title)
        assertTrue(added.task.cantMiss)
        assertEquals(at("2026-10-29 11:00"), added.task.createdAt)
        assertEquals(listOf<EditEvent>(EditEvent.Saved(added = true)), events)
        assertEquals("2026-10-29 13:00 regular", h.nextAlarm(added.id))
    }

    @Test
    fun `what was typed survives the process dying`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val handle = SavedStateHandle()
        h.viewModel(handle = handle).update { it.copy(title = "Pay rent", repeat = Repeat.MONTHLY, intervalHours = 4) }

        val restored = h.viewModel(handle = handle).form!!

        assertEquals("Pay rent", restored.title)
        assertEquals(Repeat.MONTHLY, restored.repeat)
        assertEquals(4, restored.intervalHours)
    }

    @Test
    fun `editing loads the task and saves the change`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val id = h.add(task(title = "Call the bank"))
        val taskId = h.store.occurrence(id)!!.task.id
        val vm = h.viewModel(taskId = taskId)

        assertEquals("Call the bank", vm.form?.title)
        vm.update { it.copy(title = "Call the bank about the card", startTime = LocalTime.of(12, 0)) }
        vm.save()

        val edited = h.store.task(taskId)!!
        assertEquals("Call the bank about the card", edited.title)
        assertEquals(at("2026-10-29 09:00"), edited.createdAt)
        assertEquals("2026-10-29 12:00 regular", h.nextAlarm(id))
    }
}
