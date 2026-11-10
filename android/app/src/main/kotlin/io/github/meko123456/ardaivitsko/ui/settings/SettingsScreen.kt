package io.github.meko123456.ardaivitsko.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.meko123456.ardaivitsko.calendar.CalendarInfo
import io.github.meko123456.ardaivitsko.core.NUDGE_INTERVALS
import io.github.meko123456.ardaivitsko.ui.components.LocalFormats
import io.github.meko123456.ardaivitsko.ui.components.PickerRow
import io.github.meko123456.ardaivitsko.ui.components.SectionHeader
import io.github.meko123456.ardaivitsko.ui.components.SwitchRow
import io.github.meko123456.ardaivitsko.ui.components.TimePickerDialog
import io.github.meko123456.ardaivitsko.ui.intervalLabel

private enum class SettingsPicker { QUIET_START, QUIET_END, DEFAULT_START, DEFAULT_DEADLINE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    versionName: String,
    bottomBar: @Composable () -> Unit,
    onFixNotifications: () -> Unit,
    onFixExactAlarms: () -> Unit,
    onFixFullScreen: () -> Unit,
    onFixBattery: () -> Unit,
    onFixCalendar: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formats = LocalFormats.current
    var picking by rememberSaveable { mutableStateOf<SettingsPicker?>(null) }
    var choosingCalendar by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAbilities()
        onPauseOrDispose { }
    }
    val settings = state.settings

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, bottomBar = bottomBar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Quiet hours")
            SwitchRow(
                label = "Quiet hours",
                supporting = "No regular nudges in these hours. A can't-miss task's last hours, alarm and heads-up still come.",
                checked = settings.quietHoursOn,
                onCheckedChange = viewModel::setQuietHoursOn,
            )
            PickerRow("From", formats.time(settings.quietHours.start), onClick = { picking = SettingsPicker.QUIET_START }, enabled = settings.quietHoursOn)
            PickerRow("To", formats.time(settings.quietHours.end), onClick = { picking = SettingsPicker.QUIET_END }, enabled = settings.quietHoursOn)

            SectionHeader("New tasks")
            PickerRow("Nudging starts", formats.time(settings.defaults.startTime), onClick = { picking = SettingsPicker.DEFAULT_START })
            PickerRow("Deadline", formats.time(settings.defaults.deadline), onClick = { picking = SettingsPicker.DEFAULT_DEADLINE })
            Text("How often", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                NUDGE_INTERVALS.forEach { hours ->
                    FilterChip(
                        selected = settings.defaults.intervalHours == hours,
                        onClick = { viewModel.setDefaultInterval(hours) },
                        label = { Text(intervalLabel(hours)) },
                    )
                }
            }

            SectionHeader("Google Calendar")
            Text(
                "A task you say can also go into Google Calendar, and the events of the calendars you choose " +
                    "become tasks here, so an assistant that adds to your calendar adds here too. Done and a move go back to the event.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (!state.abilities.calendar) {
                AbilityRow(
                    title = "Calendar access",
                    allowedText = "Allowed",
                    blockedText = "Off: the calendar can't be read or written.",
                    allowed = false,
                    fixLabel = "Allow",
                    onFix = onFixCalendar,
                )
            } else if (state.calendars.isEmpty()) {
                Text(
                    "No calendars on this phone. Add a Google account with Calendar on in the phone's settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                val writeCalendar = state.calendars.firstOrNull { it.id == settings.calendar.writeCalendarId }
                PickerRow("Add spoken tasks to", writeCalendar?.name ?: "Nowhere", onClick = { choosingCalendar = true })
                Text("Take tasks from", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                state.calendars.forEach { calendar ->
                    SwitchRow(
                        label = calendar.name,
                        supporting = calendar.account.takeIf { it.isNotBlank() && it != calendar.name },
                        checked = calendar.id in settings.calendar.watchedCalendarIds,
                        onCheckedChange = { on -> viewModel.setWatched(calendar.id, on) },
                    )
                }
            }

            SectionHeader("What the phone allows")
            AbilityRow(
                title = "Notifications",
                allowedText = "Allowed",
                blockedText = "Off: no nudge can reach you.",
                allowed = state.abilities.notifications,
                fixLabel = "Turn on",
                onFix = onFixNotifications,
            )
            AbilityRow(
                title = "Exact alarms",
                allowedText = "Allowed: nudges come on time.",
                blockedText = "Not allowed: nudges may come late.",
                allowed = state.abilities.exactAlarms,
                fixLabel = "Allow",
                onFix = onFixExactAlarms,
            )
            AbilityRow(
                title = "Full-screen alerts",
                allowedText = "Allowed: a can't-miss alarm fills the screen.",
                blockedText = "Off: a can't-miss alarm shows only as a notification.",
                allowed = state.abilities.fullScreen,
                fixLabel = "Allow",
                onFix = onFixFullScreen,
            )
            AbilityRow(
                title = "Battery",
                allowedText = "Unrestricted: the phone won't put nudges to sleep.",
                blockedText = "Optimized: the phone may hold nudges back to save battery.",
                allowed = state.abilities.batteryUnrestricted,
                fixLabel = "Allow",
                onFix = onFixBattery,
            )

            SectionHeader("About")
            Text(
                "Ar Daivitsko $versionName. არ დაივიწყო, “don't forget”: a reminder that keeps asking until you answer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    val pickerTitle = when (picking) {
        SettingsPicker.QUIET_START -> "Quiet from"
        SettingsPicker.QUIET_END -> "Quiet until"
        SettingsPicker.DEFAULT_START -> "Nudging starts"
        SettingsPicker.DEFAULT_DEADLINE -> "Deadline"
        null -> null
    }
    val current = when (picking) {
        SettingsPicker.QUIET_START -> settings.quietHours.start
        SettingsPicker.QUIET_END -> settings.quietHours.end
        SettingsPicker.DEFAULT_START -> settings.defaults.startTime
        SettingsPicker.DEFAULT_DEADLINE -> settings.defaults.deadline
        null -> null
    }
    if (choosingCalendar) {
        CalendarChoiceDialog(
            calendars = state.calendars.filter { it.writable },
            chosen = settings.calendar.writeCalendarId,
            onDismiss = { choosingCalendar = false },
            onChoose = { id ->
                choosingCalendar = false
                viewModel.setWriteCalendar(id)
            },
        )
    }
    if (pickerTitle != null && current != null) {
        TimePickerDialog(
            title = pickerTitle,
            initial = current,
            is24Hour = formats.is24Hour(),
            onDismiss = { picking = null },
            onPick = { time ->
                when (picking) {
                    SettingsPicker.QUIET_START -> viewModel.setQuietStart(time)
                    SettingsPicker.QUIET_END -> viewModel.setQuietEnd(time)
                    SettingsPicker.DEFAULT_START -> viewModel.setDefaultStart(time)
                    SettingsPicker.DEFAULT_DEADLINE -> viewModel.setDefaultDeadline(time)
                    null -> Unit
                }
                picking = null
            },
        )
    }
}

@Composable
private fun AbilityRow(title: String, allowedText: String, blockedText: String, allowed: Boolean, fixLabel: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (allowed) allowedText else blockedText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (!allowed) {
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onFix) { Text(fixLabel) }
        }
    }
}

/** "Add spoken tasks to": one of the calendars the app may write to, or nowhere. */
@Composable
private fun CalendarChoiceDialog(calendars: List<CalendarInfo>, chosen: Long?, onDismiss: () -> Unit, onChoose: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add spoken tasks to") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                CalendarChoice("Nowhere", null, selected = chosen == null) { onChoose(null) }
                calendars.forEach { calendar ->
                    CalendarChoice(calendar.name, calendar.account.takeIf { it.isNotBlank() && it != calendar.name }, selected = chosen == calendar.id) { onChoose(calendar.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CalendarChoice(name: String, account: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (account != null) Text(account, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
