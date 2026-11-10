package io.github.meko123456.ardaivitsko

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.meko123456.ardaivitsko.nudge.Intents
import io.github.meko123456.ardaivitsko.ui.AppNavHost
import io.github.meko123456.ardaivitsko.ui.OpenRequest
import io.github.meko123456.ardaivitsko.ui.theme.ArDaivitskoTheme
import io.github.meko123456.ardaivitsko.widget.publishWidgetPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** A task to open, from a notification or the widget, until the screens have opened it. */
    private val openRequest = MutableStateFlow<OpenRequest?>(null)

    /** The launcher's "Say a task" shortcut, until the quick-add screen has opened for it. */
    private val sayTaskRequest = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity is handed the intent it was first opened with: only a fresh start reads it.
        if (savedInstanceState == null) handle(intent)
        val container = appContainer
        container.appScope.launch { runCatching { publishWidgetPreview(applicationContext) } }
        setContent {
            ArDaivitskoTheme {
                AppNavHost(
                    container = appContainer,
                    openRequest = openRequest,
                    onOpened = { opened -> openRequest.compareAndSet(opened, null) },
                    sayTaskRequest = sayTaskRequest,
                    onSayTaskOpened = { request -> sayTaskRequest.compareAndSet(request, null) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // The safety net: everything is scheduled again whenever the app opens, and the calendar
        // channel looks for events that became tasks meanwhile.
        val container = appContainer
        container.appScope.launch { container.service.reconcile() }
        container.calendarSync.requestSync()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == Intents.ACTION_SAY_TASK) {
            sayTaskRequest.value = System.nanoTime()
            return
        }
        val occurrenceId = Intents.occurrenceId(intent) ?: return
        when (intent.action) {
            Intents.ACTION_OPEN -> openRequest.value = OpenRequest(occurrenceId, confirmMove = false)
            Intents.ACTION_CONFIRM_MOVE -> openRequest.value = OpenRequest(occurrenceId, confirmMove = true)
        }
    }
}
