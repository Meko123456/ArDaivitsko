package io.github.meko123456.ardaivitsko.ui.settings

import io.github.meko123456.ardaivitsko.Harness
import io.github.meko123456.ardaivitsko.MainDispatcherRule
import io.github.meko123456.ardaivitsko.at
import io.github.meko123456.ardaivitsko.keepCollecting
import io.github.meko123456.ardaivitsko.nudge.Abilities
import io.github.meko123456.ardaivitsko.task
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `quiet hours move the next nudge and fire nothing held back`() = runTest {
        val h = Harness(at("2026-10-29 21:00"))
        val id = h.add(task())
        h.advanceTo("2026-10-29 23:00")
        val vm = SettingsViewModel(h.settings, h.service) { Abilities(notifications = true, exactAlarms = true, fullScreen = true) }
        keepCollecting(vm.state)

        vm.setQuietHoursOn(false)

        assertFalse(vm.state.value.settings.quietHoursOn)
        assertTrue(h.notifier.posted.isEmpty())

        vm.setQuietHoursOn(true)
        vm.setQuietEnd(LocalTime.of(11, 0))

        assertEquals(LocalTime.of(11, 0), vm.state.value.settings.quietHours.end)
        assertEquals("2026-10-30 13:00 overdue", h.nextAlarm(id))
    }

    @Test
    fun `defaults for new tasks are saved`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        val vm = SettingsViewModel(h.settings, h.service) { Abilities(notifications = true, exactAlarms = true, fullScreen = true) }
        keepCollecting(vm.state)

        vm.setDefaultInterval(2)
        vm.setDefaultStart(LocalTime.of(9, 0))
        vm.setDefaultDeadline(LocalTime.of(18, 0))

        val defaults = h.settings.state.value.defaults
        assertEquals(2, defaults.intervalHours)
        assertEquals(LocalTime.of(9, 0), defaults.startTime)
        assertEquals(LocalTime.of(18, 0), defaults.deadline)
    }

    @Test
    fun `what the phone allows is read again on resume`() = runTest {
        val h = Harness(at("2026-10-29 09:00"))
        var allowed = Abilities(notifications = false, exactAlarms = true, fullScreen = false)
        val vm = SettingsViewModel(h.settings, h.service) { allowed }
        keepCollecting(vm.state)

        assertFalse(vm.state.value.abilities.notifications)

        allowed = Abilities(notifications = true, exactAlarms = true, fullScreen = true)
        vm.refreshAbilities()

        assertTrue(vm.state.value.abilities.notifications)
        assertTrue(vm.state.value.abilities.fullScreen)
    }
}
