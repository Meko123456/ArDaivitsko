package io.github.meko123456.ardaivitsko.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.meko123456.ardaivitsko.core.NUDGE_INTERVALS
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.ui.components.DayPickerDialog
import io.github.meko123456.ardaivitsko.ui.components.LocalFormats
import io.github.meko123456.ardaivitsko.ui.components.PickerRow
import io.github.meko123456.ardaivitsko.ui.components.SectionHeader
import io.github.meko123456.ardaivitsko.ui.components.SwitchRow
import io.github.meko123456.ardaivitsko.ui.components.TimePickerDialog
import io.github.meko123456.ardaivitsko.ui.intervalLabel
import io.github.meko123456.ardaivitsko.ui.monthlyLabel
import java.time.format.TextStyle
import java.util.Locale

private enum class Picking { DAY, DEADLINE, START }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskScreen(viewModel: EditTaskViewModel, onClose: () -> Unit, onSaved: (added: Boolean) -> Unit) {
    val form = viewModel.form
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is EditEvent.Saved -> onSaved(event.added)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New task" else "Edit task") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close without saving") }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = form?.canSave == true && !saving) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (form == null) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { title -> viewModel.update { it.copy(title = title) } },
                    label = { Text("Title") },
                    placeholder = { Text("Pay the credit card bill") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { note -> viewModel.update { it.copy(note = note) } },
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader("When")
            PickerRow(if (form.repeat == Repeat.NEVER) "Day" else "First day", formats.longDate(form.day), onClick = { picking = Picking.DAY })
            PickerRow("Deadline", formats.time(form.deadline), onClick = { picking = Picking.DEADLINE })
            SwitchRow(
                label = "Can't miss",
                supporting = "The evening before, a heads-up. In the last three hours, a nudge every half hour, then a full-screen alarm.",
                checked = form.cantMiss,
                onCheckedChange = { on -> viewModel.update { it.copy(cantMiss = on) } },
            )

            SectionHeader("Repeat")
            ChipRow {
                Repeat.entries.forEach { repeat ->
                    FilterChip(
                        selected = form.repeat == repeat,
                        onClick = { viewModel.update { it.copy(repeat = repeat) } },
                        label = { Text(repeat.label()) },
                    )
                }
            }
            repeatHint(form)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SectionHeader("Nudges")
            PickerRow("Nudging starts", formats.time(form.startTime), onClick = { picking = Picking.START })
            Text("How often", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ChipRow {
                NUDGE_INTERVALS.forEach { hours ->
                    FilterChip(
                        selected = form.intervalHours == hours,
                        onClick = { viewModel.update { it.copy(intervalHours = hours) } },
                        label = { Text(intervalLabel(hours)) },
                    )
                }
            }
            Text(
                "Until you answer. In progress halves it; quiet hours from Settings hold regular nudges back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Button(
                onClick = viewModel::save,
                enabled = form.canSave && !saving,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(if (viewModel.isNew) "Add task" else "Save changes") }
            Spacer(Modifier.height(16.dp))
        }
    }

    when (picking) {
        Picking.DAY -> DayPickerDialog(
            initial = form?.day ?: return,
            onDismiss = { picking = null },
            onPick = { day ->
                picking = null
                viewModel.update { it.copy(day = day) }
            },
        )
        Picking.DEADLINE -> TimePickerDialog(
            title = "Deadline",
            initial = form?.deadline ?: return,
            is24Hour = formats.is24Hour(),
            onDismiss = { picking = null },
            onPick = { time ->
                picking = null
                viewModel.update { it.copy(deadline = time) }
            },
        )
        Picking.START -> TimePickerDialog(
            title = "Nudging starts",
            initial = form?.startTime ?: return,
            is24Hour = formats.is24Hour(),
            onDismiss = { picking = null },
            onPick = { time ->
                picking = null
                viewModel.update { it.copy(startTime = time) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    // A FlowRow, so large text wraps the chips instead of squeezing the last one.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { content() }
}

private fun Repeat.label(): String = when (this) {
    Repeat.NEVER -> "Never"
    Repeat.DAILY -> "Daily"
    Repeat.WEEKLY -> "Weekly"
    Repeat.MONTHLY -> "Monthly"
}

private fun repeatHint(form: TaskForm): String? = when (form.repeat) {
    Repeat.NEVER -> null
    Repeat.DAILY -> "Every day from the first day. Each day's own task nudges by itself."
    Repeat.WEEKLY -> "Every ${form.day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}."
    Repeat.MONTHLY -> monthlyLabel(form.day.dayOfMonth) + "."
}
