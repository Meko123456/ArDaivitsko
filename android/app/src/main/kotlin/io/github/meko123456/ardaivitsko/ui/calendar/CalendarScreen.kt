package io.github.meko123456.ardaivitsko.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.meko123456.ardaivitsko.ui.components.LocalFormats
import io.github.meko123456.ardaivitsko.ui.today.CantMissBadge
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    bottomBar: @Composable () -> Unit,
    onOpenOccurrence: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    onAdd: (LocalDate?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Calendar") }) },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add task") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Add task") },
                onClick = { onAdd(state.selected) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            item(key = "month") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.showMonth(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(state.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.showMonth(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }
            }
            item(key = "grid") { MonthGrid(state, onSelect = viewModel::select) }
            item(key = "day") {
                Text(
                    state.selectedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing on this day.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(state.items, key = { "${it.occurrenceId}-${it.taskId}-${it.title}" }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(CardDefaults.shape)
                        .clickable(role = Role.Button) { item.occurrenceId?.let(onOpenOccurrence) ?: onOpenTask(item.taskId) },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (item.cantMiss && !item.done) CantMissBadge()
                        }
                        Text(item.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(state: CalendarState, onSelect: (LocalDate) -> Unit) {
    val formats = LocalFormats.current
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            state.weekdays.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
        }
        state.cells.chunked(7).forEach { week ->
            Row {
                week.forEach { cell ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (cell != null) {
                            DayBox(cell, label = formats.longDate(cell.date), onClick = { onSelect(cell.date) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayBox(cell: DayCell, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (cell.selected) colors.primary else colors.surface
    val content = if (cell.selected) colors.onPrimary else colors.onSurface
    val markLabel = when (cell.mark) {
        Mark.OPEN -> ", tasks open"
        Mark.ALL_DONE -> ", all done"
        null -> ""
    }
    Column(
        Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(background)
            .then(if (cell.isToday && !cell.selected) Modifier.border(BorderStroke(1.dp, colors.primary), CircleShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label + markLabel
                selected = cell.selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(cell.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = content)
        Spacer(Modifier.height(2.dp))
        val markColor = if (cell.selected) colors.onPrimary else colors.primary
        when (cell.mark) {
            Mark.OPEN -> Box(Modifier.size(6.dp).clip(CircleShape).background(markColor))
            Mark.ALL_DONE -> Box(Modifier.size(6.dp).border(1.dp, markColor, CircleShape))
            null -> Spacer(Modifier.size(6.dp))
        }
    }
}
