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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** A task to open, from a notification or the widget, until the screens have opened it. */
    private val openRequest = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity is handed the intent it was first opened with: only a fresh start reads it.
        if (savedInstanceState == null) handle(intent)
        setContent {
            ArDaivitskoTheme {
                AppNavHost(
                    container = appContainer,
                    openRequest = openRequest,
                    onOpened = { opened -> openRequest.compareAndSet(opened, null) },
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
        // The safety net: everything is scheduled again whenever the app opens.
        val container = appContainer
        container.appScope.launch { container.service.reconcile() }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val occurrenceId = Intents.occurrenceId(intent) ?: return
        when (intent.action) {
            Intents.ACTION_OPEN -> openRequest.value = OpenRequest(occurrenceId, confirmMove = false)
            Intents.ACTION_CONFIRM_MOVE -> openRequest.value = OpenRequest(occurrenceId, confirmMove = true)
        }
    }
}
