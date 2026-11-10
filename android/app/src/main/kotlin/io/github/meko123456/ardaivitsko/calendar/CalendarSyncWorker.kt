package io.github.meko123456.ardaivitsko.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.meko123456.ardaivitsko.appContainer
import java.util.concurrent.TimeUnit

/**
 * The background check of the watched calendars, every fifteen minutes (WorkManager's shortest),
 * so an event added by an assistant becomes a task before its nudges would have started. The app
 * also checks whenever it opens.
 */
class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.appContainer.calendarSync.syncNow()
        return Result.success()
    }

    companion object {
        private const val NAME = "calendar-sync"

        fun schedule(context: Context, on: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (on) {
                workManager.enqueueUniquePeriodicWork(
                    NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<CalendarSyncWorker>(15, TimeUnit.MINUTES).build(),
                )
            } else {
                workManager.cancelUniqueWork(NAME)
            }
        }
    }
}
