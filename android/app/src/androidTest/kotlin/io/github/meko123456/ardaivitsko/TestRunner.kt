package io.github.meko123456.ardaivitsko

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.github.meko123456.ardaivitsko.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Starts the app as [TestApp], so instrumented tests never touch the tasks on the phone. */
class TestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestApp::class.java.name, context)
}

/** The real app with an in-memory database and its own settings file; everything else is real. */
class TestApp : ArDaivitskoApp() {
    override fun createContainer(): AppContainer =
        AppContainer.create(this, database = AppDatabase.inMemory(this), settingsFile = "settings-test")
}

/** Deletes every task, through the service, so their alarms and notifications go too. */
fun AppContainer.deleteAllTasks() = runBlocking {
    store.observeSeries().first().forEach { service.deleteTask(it.task.id) }
}
