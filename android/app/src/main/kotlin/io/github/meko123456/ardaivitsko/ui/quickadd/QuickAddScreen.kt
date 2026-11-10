package io.github.meko123456.ardaivitsko.ui.quickadd

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.meko123456.ardaivitsko.R
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.ui.components.DayPickerDialog
import io.github.meko123456.ardaivitsko.ui.components.LocalFormats
import io.github.meko123456.ardaivitsko.ui.components.PickerRow
import io.github.meko123456.ardaivitsko.ui.components.SectionHeader
import io.github.meko123456.ardaivitsko.ui.components.SwitchRow
import io.github.meko123456.ardaivitsko.ui.components.TimePickerDialog
import io.github.meko123456.ardaivitsko.ui.monthlyLabel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private enum class Picking { DAY, TIME }

/** The title line, for tests. */
const val QUICK_ADD_TITLE_TAG = "quick-add-title"

/**
 * Say a task, or type it. The phone's own speech recognizer turns speech into words; the words
 * are read as a task underneath, with its day, time and repeat each changeable by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddScreen(viewModel: QuickAddViewModel, onClose: () -> Unit, onAdded: (title: String, inCalendar: Boolean?) -> Unit) {
    val state = viewModel.state
    val formats = LocalFormats.current
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) viewModel.heard(heard)
    }
    val listen = {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a task, like “renew the parking permit on Thursday at 10”"),
            )
        } catch (_: ActivityNotFoundException) {
            viewModel.noRecognizer()
        }
    }

    LaunchedEffect(viewModel) {
        if (viewModel.takeListenRequest()) listen()
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is QuickAddEvent.Added -> onAdded(event.title, event.inCalendar)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Say a task") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close without adding") }
                },
                actions = {
                    TextButton(onClick = viewModel::add, enabled = state.canAdd) { Text("Add") }
                },
            )
        },
    ) { padding ->
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
                    value = state.sentence,
                    onValueChange = viewModel::setSentence,
                    label = { Text("Say or type a task") },
                    placeholder = { Text("Renew the parking permit on Thursday at 10") },
                    trailingIcon = {
                        IconButton(onClick = listen) { Icon(painterResource(R.drawable.ic_mic), contentDescription = "Say it") }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Days, times and repeats in plain words: tomorrow at 6, next Monday, on the 15th, every week.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Announced to TalkBack when it appears; one node that changes its words.
                Text(
                    state.notice.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.then(if (state.notice == null) Modifier.height(0.dp) else Modifier),
                )
            }

            SectionHeader("Understood as")
            Text(
                state.title.ifBlank { "No task yet" },
                style = MaterialTheme.typography.titleMedium,
                color = if (state.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(QUICK_ADD_TITLE_TAG),
            )
            PickerRow(if (state.repeat == Repeat.NEVER) "Day" else "First day", formats.longDate(state.day), onClick = { picking = Picking.DAY })
            PickerRow("Time", state.time?.let(formats::time) ?: "None: by the day's end", onClick = { picking = Picking.TIME })
            if (state.time != null) {
                TextButton(onClick = { viewModel.pickTime(null) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("No time") }
            }
            Text("Repeat", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                Repeat.entries.forEach { repeat ->
                    FilterChip(
                        selected = state.repeat == repeat,
                        onClick = { viewModel.pickRepeat(repeat) },
                        label = { Text(repeatChip(repeat, state.day)) },
                    )
                }
            }

            SectionHeader("Google Calendar")
            if (state.calendarName != null) {
                SwitchRow(
                    label = "Also in Google Calendar",
                    supporting = "As an event in “${state.calendarName}”. Done and a move follow it there.",
                    checked = state.toCalendar,
                    onCheckedChange = viewModel::setToCalendar,
                )
            } else {
                Text(
                    "To put spoken tasks in Google Calendar too, choose a calendar in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Button(onClick = viewModel::add, enabled = state.canAdd, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Add task") }
            Spacer(Modifier.height(16.dp))
        }
    }

    when (picking) {
        Picking.DAY -> DayPickerDialog(
            initial = state.day,
            onDismiss = { picking = null },
            onPick = { day: LocalDate ->
                picking = null
                viewModel.pickDay(day)
            },
        )
        Picking.TIME -> TimePickerDialog(
            title = "Time",
            initial = state.time ?: LocalTime.of(10, 0),
            is24Hour = formats.is24Hour(),
            onDismiss = { picking = null },
            onPick = { time: LocalTime ->
                picking = null
                viewModel.pickTime(time)
            },
        )
        null -> Unit
    }
}

private fun repeatChip(repeat: Repeat, day: LocalDate): String = when (repeat) {
    Repeat.NEVER -> "Once"
    Repeat.DAILY -> "Every day"
    Repeat.WEEKLY -> "Every ${day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
    Repeat.MONTHLY -> monthlyLabel(day.dayOfMonth).replace("Every month on the", "Monthly on the")
}
