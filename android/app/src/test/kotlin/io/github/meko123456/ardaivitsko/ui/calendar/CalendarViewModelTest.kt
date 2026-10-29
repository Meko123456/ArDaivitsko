package io.github.meko123456.ardaivitsko.ui.calendar

import androidx.lifecycle.SavedStateHandle
import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.MainDispatcherRule
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.day
import io.github.meko123456.ardaivitsko.keepCollecting
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.task
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val defaultLocale = Locale.getDefault()

    @BeforeTest fun englishWeeks() = Locale.setDefault(Locale.UK)

    @AfterTest fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun Harness.viewModel() = CalendarViewModel(SavedStateHandle(), store, time, formats)

    private fun CalendarState.mark(date: String) = cells.filterNotNull().single { it.date == day(date) }.mark

    @Test
    fun `days with something open are filled, days with everything done are hollow`() = runTest {
        val h = Harness(at("2026-10-27 09:00"))
        val done = h.add(task(title = "Buy medicine", day = "2026-10-27"))
        h.service.answer(done, Answer.Done)
        h.add(task(title = "Pay the credit card bill", day = "2026-10-28"))

        val vm = h.viewModel()
        keepCollecting(vm.state)

        assertEquals(Mark.ALL_DONE, vm.state.value.mark("2026-10-27"))
        assertEquals(Mark.OPEN, vm.state.value.mark("2026-10-28"))
        assertNull(vm.state.value.mark("2026-10-29"))
        assertEquals("October 2026", vm.state.value.title)
    }

    @Test
    fun `repeats further ahead show from the task's rule`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        h.add(task(title = "Water the plants", day = "2026-10-29", repeat = Repeat.WEEKLY))

        val vm = h.viewModel()
        keepCollecting(vm.state)
        vm.showMonth(1)

        assertEquals("November 2026", vm.state.value.title)
        assertEquals(listOf(5, 12, 19, 26), vm.state.value.cells.filterNotNull().filter { it.mark == Mark.OPEN }.map { it.date.dayOfMonth })
        vm.select(day("2026-11-12"))
        assertEquals(listOf("Water the plants"), vm.state.value.items.map { it.title })
        assertNull(vm.state.value.items.single().occurrenceId)
    }

    @Test
    fun `the grid starts on the locale's first day of the week`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))

        val vm = h.viewModel()
        keepCollecting(vm.state)

        // October 2026 starts on a Thursday; weeks in the UK start on Monday.
        assertEquals(3, vm.state.value.cells.takeWhile { it == null }.size)
        assertEquals(0, vm.state.value.cells.size % 7)
        assertEquals("M", vm.state.value.weekdays.first())
    }

    @Test
    fun `a moved task shows on its new day`() = runTest {
        val h = Harness(at("2026-10-29 11:00"))
        val id = h.add(task(title = "Renew the parking permit"))
        h.service.answer(id, Answer.NotToday)

        val vm = h.viewModel()
        keepCollecting(vm.state)

        assertNull(vm.state.value.mark("2026-10-29"))
        assertEquals(Mark.OPEN, vm.state.value.mark("2026-10-30"))
    }
}
