package io.github.meko123456.ardaivitsko.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.calendar.CalendarInfo
import io.github.meko123456.ardaivitsko.calendar.CalendarProvider
import io.github.meko123456.ardaivitsko.calendar.CalendarSync
import io.github.meko123456.ardaivitsko.core.QuietHours
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.AppSettings
import io.github.meko123456.ardaivitsko.data.CalendarSettings
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.nudge.Abilities
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

data class SettingsState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val abilities: Abilities = Abilities(notifications = true, exactAlarms = true, fullScreen = true, batteryUnrestricted = true),
    /** The phone's calendars, once calendar access is allowed. */
    val calendars: List<CalendarInfo> = emptyList(),
)

/**
 * Quiet hours and the defaults for new tasks, what the phone allows, and the Google Calendar
 * channel. The phone's answers and its calendars are read again on every resume
 * ([refreshAbilities]), since they're changed in its own settings.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val service: NudgeService,
    private val readAbilities: () -> Abilities,
    private val provider: CalendarProvider,
    private val calendarSync: CalendarSync,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val abilities = MutableStateFlow(readAbilities())
    private val calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())

    val state: StateFlow<SettingsState> = combine(settings.settings, abilities, calendars) { s, a, c ->
        SettingsState(loading = false, settings = s, abilities = a, calendars = c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    init {
        loadCalendars()
    }

    fun refreshAbilities() {
        abilities.value = readAbilities()
        loadCalendars()
        // Exact alarms or notifications may have just been allowed: set everything again.
        viewModelScope.launch { service.reconcile() }
    }

    fun setQuietHoursOn(on: Boolean) = changeQuietHours { it.copy(quietHoursOn = on) }

    fun setQuietStart(time: LocalTime) = changeQuietHours { it.copy(quietHours = QuietHours(time, it.quietHours.end)) }

    fun setQuietEnd(time: LocalTime) = changeQuietHours { it.copy(quietHours = QuietHours(it.quietHours.start, time)) }

    fun setDefaultStart(time: LocalTime) = changeDefaults { it.copy(startTime = time) }

    fun setDefaultInterval(hours: Int) = changeDefaults { it.copy(intervalHours = hours) }

    fun setDefaultDeadline(time: LocalTime) = changeDefaults { it.copy(deadline = time) }

    /** The calendar spoken tasks also go to; null for none. */
    fun setWriteCalendar(id: Long?) = changeCalendar { it.copy(writeCalendarId = id) }

    /** Whether [id]'s events become tasks here. */
    fun setWatched(id: Long, on: Boolean) = changeCalendar { it.copy(watchedCalendarIds = if (on) it.watchedCalendarIds + id else it.watchedCalendarIds - id) }

    private fun loadCalendars() {
        viewModelScope.launch {
            calendars.value = withContext(io) { if (provider.hasAccess()) provider.calendars() else emptyList() }
        }
    }

    private fun changeQuietHours(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settings.update(transform)
            service.settingsChanged()
        }
    }

    private fun changeDefaults(transform: (TaskDefaults) -> TaskDefaults) {
        viewModelScope.launch { settings.update { it.copy(defaults = transform(it.defaults)) } }
    }

    private fun changeCalendar(transform: (CalendarSettings) -> CalendarSettings) {
        viewModelScope.launch {
            settings.update { it.copy(calendar = transform(it.calendar)) }
            calendarSync.requestSync()
        }
    }
}
