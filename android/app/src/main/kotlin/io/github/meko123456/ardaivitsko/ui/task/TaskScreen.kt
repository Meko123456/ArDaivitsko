package io.github.meko123456.ardaivitsko.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.meko123456.ardaivitsko.ui.components.DayPickerDialog
import io.github.meko123456.ardaivitsko.ui.today.CantMissBadge
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(viewModel: TaskViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var picking by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                TaskEvent.Deleted -> onBack()
                is TaskEvent.Message -> {
                    val result = snackbar.showSnackbar(
                        event.text,
                        actionLabel = if (event.undoMove) "Undo" else null,
                        duration = if (event.undoMove) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoMove()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!state.missing && !state.loading) {
                        IconButton(onClick = { onEdit(state.taskId) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit task") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete task") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.missing) {
                Text("This task was deleted.", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            if (state.loading) return@Column
            Text(state.title, style = MaterialTheme.typography.headlineSmall)
            if (state.note.isNotBlank()) Text(state.note, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.cantMiss) CantMissBadge()
                state.repeat?.let { AssistChip(onClick = {}, label = { Text(it) }, enabled = false) }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.status, style = MaterialTheme.typography.titleMedium)
                    Text(state.dueLine, style = MaterialTheme.typography.bodyLarge)
                    state.nextNudge?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (state.done) {
                OutlinedButton(onClick = viewModel::reopen, modifier = Modifier.fillMaxWidth()) { Text("Mark as not done") }
            } else {
                Button(onClick = viewModel::done, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                FilledTonalButton(onClick = viewModel::inProgress, modifier = Modifier.fillMaxWidth()) { Text("In progress") }
                if (state.canMoveToTomorrow) {
                    OutlinedButton(onClick = viewModel::notToday, modifier = Modifier.fillMaxWidth()) { Text("Not today") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.canSnooze) TextButton(onClick = viewModel::snooze) { Text("Snooze 1 hour") }
                    TextButton(onClick = { picking = true }) { Text("Move to…") }
                    if (state.canUndoMove) TextButton(onClick = viewModel::undoMove) { Text("Undo move") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.confirmingMove) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirm,
            title = { Text("This is the last day") },
            text = { Text("“${state.title}” can't be missed and is due today. Move it anyway?") },
            confirmButton = { TextButton(onClick = viewModel::confirmMove) { Text("Move to tomorrow") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirm) { Text("Keep it today") } },
        )
    }
    if (picking) {
        DayPickerDialog(
            initial = state.day ?: LocalDate.now(),
            confirmLabel = "Move",
            onDismiss = { picking = false },
            onPick = { day ->
                picking = false
                viewModel.moveTo(day)
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this task?") },
            text = { Text(if (state.repeat != null) "Every day it repeats on goes with it." else "It won't nudge again.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
