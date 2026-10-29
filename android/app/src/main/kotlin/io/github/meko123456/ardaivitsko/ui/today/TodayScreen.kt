package io.github.meko123456.ardaivitsko.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.meko123456.ardaivitsko.core.OccurrenceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    notificationsAllowed: Boolean,
    bottomBar: @Composable () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onFixNotifications: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // When the day turns over while the screen is kept (the app in the background overnight), go
    // back to the top. The list keeps its first item in place by key, so the "Overdue" heading
    // that now goes above it would start out of sight.
    var shownDate by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.date) {
        if (state.date.isEmpty()) return@LaunchedEffect
        if (shownDate.isNotEmpty() && shownDate != state.date) listState.scrollToItem(0)
        shownDate = state.date
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TodayEvent.MarkedDone -> {
                    val result = snackbar.showSnackbar("Done: ${event.title}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) viewModel.reopen(event.occurrenceId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today")
                        if (state.date.isNotEmpty()) {
                            Text(state.date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // M3's extended FAB clears its text's semantics, so the label goes on the icon too.
            ExtendedFloatingActionButton(
                text = { Text("Add task") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Add task") },
                onClick = onAdd,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + FAB_CLEARANCE,
            ),
        ) {
            val hasTasks = state.overdue.isNotEmpty() || state.today.isNotEmpty()
            if (!notificationsAllowed && hasTasks) {
                item(key = "notifications-off") { NotificationsOffCard(onFixNotifications) }
            }
            if (state.overdue.isNotEmpty()) {
                item(key = "overdue-header") { Header("Overdue", MaterialTheme.colorScheme.error) }
                items(state.overdue, key = { it.occurrenceId }) { row ->
                    TaskCard(row, onOpen = { onOpen(row.occurrenceId) }, onDone = { viewModel.markDone(row) }, onReopen = { viewModel.reopen(row.occurrenceId) })
                }
            }
            // Under the title "Today", a second "Today" only helps when the overdue come first.
            if (state.overdue.isNotEmpty()) {
                item(key = "today-header") { Header("Today", MaterialTheme.colorScheme.primary) }
            }
            if (state.today.isEmpty() && !state.loading) {
                item(key = "empty") {
                    Text(
                        "Nothing for today. Add a task with the button below, and it will keep asking until you answer.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.today, key = { it.occurrenceId }) { row ->
                TaskCard(row, onOpen = { onOpen(row.occurrenceId) }, onDone = { viewModel.markDone(row) }, onReopen = { viewModel.reopen(row.occurrenceId) })
            }
        }
    }
}

private val FAB_CLEARANCE = 96.dp

@Composable
private fun Header(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun NotificationsOffCard(onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Notifications are off", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Nudges can't reach you until they're on.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        TextButton(onClick = onFix, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)) { Text("Turn on") }
    }
}

@Composable
fun TaskCard(row: TaskRow, onOpen: () -> Unit, onDone: () -> Unit, onReopen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (row.done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DoneButton(row, onDone, onReopen)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (row.done) TextDecoration.LineThrough else null,
                        color = if (row.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    if (row.cantMiss && !row.done) CantMissBadge()
                }
                Text(
                    row.dueLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(row.statusLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DoneButton(row: TaskRow, onDone: () -> Unit, onReopen: () -> Unit) {
    val icon: ImageVector = Icons.Filled.CheckCircle
    if (row.state == OccurrenceState.DONE) {
        IconButton(onClick = onReopen) {
            Icon(icon, contentDescription = "Mark not done: ${row.title}", tint = MaterialTheme.colorScheme.primary)
        }
    } else {
        IconButton(onClick = onDone) {
            Icon(icon, contentDescription = "Done: ${row.title}", tint = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun CantMissBadge(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            "Can't miss",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
