package io.github.meko123456.ardaivitsko

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.meko123456.ardaivitsko.data.DataStoreSettings
import io.github.meko123456.ardaivitsko.data.RoomTaskStore
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.data.db.AppDatabase
import io.github.meko123456.ardaivitsko.nudge.AndroidAlarms
import io.github.meko123456.ardaivitsko.nudge.AndroidNotifier
import io.github.meko123456.ardaivitsko.nudge.NudgeNotifier
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.nudge.PhoneAbilities
import io.github.meko123456.ardaivitsko.nudge.WidgetRefresher
import io.github.meko123456.ardaivitsko.time.AndroidTextFormats
import io.github.meko123456.ardaivitsko.time.SystemTimeSource
import io.github.meko123456.ardaivitsko.time.TextFormats
import io.github.meko123456.ardaivitsko.time.TimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's object graph, wired by hand: one per process, made by [ArDaivitskoApp]. Screens,
 * receivers, the widget and the alarm screen all take what they need from it.
 */
class AppContainer(
    val context: Context,
    database: AppDatabase,
    settingsDataStore: DataStore<Preferences>,
    val time: TimeSource,
    val appScope: CoroutineScope,
) {
    val store: TaskStore = RoomTaskStore(database)
    val settings: SettingsStore = DataStoreSettings(settingsDataStore)
    val formats: TextFormats = AndroidTextFormats(context)
    val notifier: NudgeNotifier = AndroidNotifier(context, time, formats)
    val alarms = AndroidAlarms(context)
    val abilities = PhoneAbilities(context)
    val service = NudgeService(store, settings, time, notifier, alarms, WidgetRefresher { })

    /** Runs [block] for a receiver after onReceive returns, and lets the system know when it's done. */
    fun runAsync(pending: BroadcastReceiver.PendingResult, block: suspend () -> Unit) {
        appScope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MILLIS) { block() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** A receiver has about ten seconds; scheduling takes milliseconds. */
        private const val RECEIVER_BUDGET_MILLIS = 8_000L

        fun create(
            context: Context,
            time: TimeSource = SystemTimeSource,
            database: AppDatabase = AppDatabase.open(context),
            settingsFile: String = "settings",
        ): AppContainer {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e("ArDaivitsko", "Background work failed", error) },
            )
            val settings = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope + Dispatchers.IO,
                produceFile = { context.preferencesDataStoreFile(settingsFile) },
            )
            return AppContainer(context, database, settings, time, scope)
        }
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as ArDaivitskoApp).container
