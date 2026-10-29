package io.github.meko123456.ardaivitsko.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.core.QuietHours
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.nudge.Abilities
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

data class SettingsState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val abilities: Abilities = Abilities(notifications = true, exactAlarms = true, fullScreen = true),
)

/**
 * Quiet hours and the defaults for new tasks, plus what the phone allows. The phone's answers are
 * read again on every resume ([refreshAbilities]), since they're changed in its own settings.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val service: NudgeService,
    private val readAbilities: () -> Abilities,
) : ViewModel() {

    private val abilities = MutableStateFlow(readAbilities())

    val state: StateFlow<SettingsState> = combine(settings.settings, abilities) { s, a -> SettingsState(loading = false, settings = s, abilities = a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun refreshAbilities() {
        abilities.value = readAbilities()
        // Exact alarms or notifications may have just been allowed: set everything again.
        viewModelScope.launch { service.reconcile() }
    }

    fun setQuietHoursOn(on: Boolean) = changeQuietHours { it.copy(quietHoursOn = on) }

    fun setQuietStart(time: LocalTime) = changeQuietHours { it.copy(quietHours = QuietHours(time, it.quietHours.end)) }

    fun setQuietEnd(time: LocalTime) = changeQuietHours { it.copy(quietHours = QuietHours(it.quietHours.start, time)) }

    fun setDefaultStart(time: LocalTime) = changeDefaults { it.copy(startTime = time) }

    fun setDefaultInterval(hours: Int) = changeDefaults { it.copy(intervalHours = hours) }

    fun setDefaultDeadline(time: LocalTime) = changeDefaults { it.copy(deadline = time) }

    private fun changeQuietHours(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settings.update(transform)
            service.settingsChanged()
        }
    }

    private fun changeDefaults(transform: (TaskDefaults) -> TaskDefaults) {
        viewModelScope.launch { settings.update { it.copy(defaults = transform(it.defaults)) } }
    }
}
