package io.github.meko123456.ardaivitsko

import android.app.Application
import io.github.meko123456.ardaivitsko.nudge.Channels

open class ArDaivitskoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        Channels.create(this)
    }

    /** Tests start the app with an in-memory database instead (see TestRunner). */
    protected open fun createContainer(): AppContainer = AppContainer.create(this)
}
