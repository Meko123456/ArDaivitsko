package io.github.meko123456.ardaivitsko.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.meko123456.ardaivitsko.core.NUDGE_INTERVALS
import io.github.meko123456.ardaivitsko.core.NudgeSettings
import io.github.meko123456.ardaivitsko.core.QuietHours
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/** Quiet hours (default 22:00 to 08:00, and they can be off) and what a new task starts with. */
data class AppSettings(
    val quietHoursOn: Boolean = true,
    val quietHours: QuietHours = QuietHours.DEFAULT,
    val defaults: TaskDefaults = TaskDefaults(),
) {
    val nudgeSettings: NudgeSettings get() = NudgeSettings(quietHours.takeIf { quietHoursOn })
}

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * Settings in a Preferences DataStore, one key per value. A value that can't be read falls back to
 * its default rather than failing, and a corrupt file is replaced (see AppContainer).
 */
class DataStoreSettings(private val dataStore: DataStore<Preferences>) : SettingsStore {

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences -> preferences.write(transform(preferences.toSettings())) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val fallback = AppSettings()
        fun time(key: Preferences.Key<String>, default: LocalTime) =
            this[key]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: default
        return AppSettings(
            quietHoursOn = this[QUIET_ON] ?: fallback.quietHoursOn,
            quietHours = QuietHours(time(QUIET_START, fallback.quietHours.start), time(QUIET_END, fallback.quietHours.end)),
            defaults = TaskDefaults(
                deadline = time(DEFAULT_DEADLINE, fallback.defaults.deadline),
                startTime = time(DEFAULT_START, fallback.defaults.startTime),
                intervalHours = this[DEFAULT_INTERVAL]?.takeIf { it in NUDGE_INTERVALS } ?: fallback.defaults.intervalHours,
            ),
        )
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[QUIET_ON] = settings.quietHoursOn
        this[QUIET_START] = settings.quietHours.start.toString()
        this[QUIET_END] = settings.quietHours.end.toString()
        this[DEFAULT_DEADLINE] = settings.defaults.deadline.toString()
        this[DEFAULT_START] = settings.defaults.startTime.toString()
        this[DEFAULT_INTERVAL] = settings.defaults.intervalHours
    }

    private companion object {
        val QUIET_ON = booleanPreferencesKey("quiet_hours_on")
        val QUIET_START = stringPreferencesKey("quiet_hours_start")
        val QUIET_END = stringPreferencesKey("quiet_hours_end")
        val DEFAULT_DEADLINE = stringPreferencesKey("default_deadline")
        val DEFAULT_START = stringPreferencesKey("default_start")
        val DEFAULT_INTERVAL = intPreferencesKey("default_interval_hours")
    }
}
