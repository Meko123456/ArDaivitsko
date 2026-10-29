package io.github.meko123456.ardaivitsko.ui.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.Nudge
import io.github.meko123456.ardaivitsko.core.NudgeKind
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.nudge.Intents
import io.github.meko123456.ardaivitsko.ui.theme.ArDaivitskoTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Can't-miss alarm, full screen like an alarm clock, over the lock screen. The notification's
 * sound repeats until it's answered here (or on the notification), or stopped.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        val occurrenceId = Intents.occurrenceId(intent) ?: return finish()
        val container = appContainer
        setContent {
            ArDaivitskoTheme {
                val item by container.store.observeOccurrence(occurrenceId).collectAsStateWithLifecycle(initialValue = null)
                // Answered elsewhere (the notification, the widget) or deleted: nothing left to ring for.
                LaunchedEffect(occurrenceId) {
                    val current = container.store.observeOccurrence(occurrenceId).first { it == null || it.isClosed }
                    if (current == null || current.isClosed) finish()
                }
                AlarmScreen(
                    item = item,
                    whyNow = item?.let {
                        container.formats.texts(container.time.zone()).whyNow(NudgeKind.ALARM, it.occurrence, container.time.now())
                    }.orEmpty(),
                    deadline = item?.let { container.formats.time(it.task.deadline) }.orEmpty(),
                    onDone = { answer(occurrenceId, Answer.Done) },
                    onInProgress = { answer(occurrenceId, Answer.InProgress) },
                    onStop = { stop(item) },
                )
            }
        }
    }

    private fun answer(occurrenceId: Long, answer: Answer) {
        val container = appContainer
        lifecycleScope.launch {
            container.service.answer(occurrenceId, answer)
            finish()
        }
    }

    /** Stops the sound; the task stays in the shade, quietly, until it's answered. */
    private fun stop(item: OccurrenceItem?) {
        val container = appContainer
        if (item != null) {
            container.notifier.cancel(item.id)
            container.notifier.post(item, Nudge(container.time.now(), NudgeKind.ALARM), container.time.now(), silent = true)
        }
        finish()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun AlarmScreen(
    item: OccurrenceItem?,
    whyNow: String,
    deadline: String,
    onDone: () -> Unit,
    onInProgress: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.height(64.dp),
            )
            Text(
                whyNow,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                item?.task?.title.orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            if (deadline.isNotEmpty()) {
                Text(
                    "Can't miss: due today by $deadline",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
            // On the alarm's own pink, the tonal default all but disappears: a light container stands out.
            FilledTonalButton(
                onClick = onInProgress,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("In progress") }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop the alarm") }
        }
    }
}
