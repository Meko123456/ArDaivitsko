package io.github.meko123456.ardaivitsko.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.meko123456.ardaivitsko.AppContainer
import io.github.meko123456.ardaivitsko.calendar.AndroidCalendarProvider
import io.github.meko123456.ardaivitsko.ui.calendar.CalendarScreen
import io.github.meko123456.ardaivitsko.ui.calendar.CalendarViewModel
import io.github.meko123456.ardaivitsko.ui.components.Formats
import io.github.meko123456.ardaivitsko.ui.components.LocalFormats
import io.github.meko123456.ardaivitsko.ui.edit.EditTaskScreen
import io.github.meko123456.ardaivitsko.ui.edit.EditTaskViewModel
import io.github.meko123456.ardaivitsko.ui.quickadd.QuickAddScreen
import io.github.meko123456.ardaivitsko.ui.quickadd.QuickAddViewModel
import io.github.meko123456.ardaivitsko.ui.settings.SettingsScreen
import io.github.meko123456.ardaivitsko.ui.settings.SettingsViewModel
import io.github.meko123456.ardaivitsko.ui.task.TaskScreen
import io.github.meko123456.ardaivitsko.ui.task.TaskViewModel
import io.github.meko123456.ardaivitsko.ui.today.TodayScreen
import io.github.meko123456.ardaivitsko.ui.today.TodayViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data object TodayRoute

@Serializable data object CalendarRoute

@Serializable data object SettingsRoute

@Serializable data class TaskRoute(val occurrenceId: Long, val confirmMove: Boolean = false)

/** A new task when [taskId] is 0, on [day] (ISO) if given. */
@Serializable data class EditRoute(val taskId: Long = 0L, val day: String? = null)

/** Say or type a task; [listen] starts the phone's speech recognizer at once. */
@Serializable data class QuickAddRoute(val listen: Boolean = false)

/** A task to open from a notification or the widget. Each tap is its own request, even for the same task. */
data class OpenRequest(val occurrenceId: Long, val confirmMove: Boolean, val serial: Long = System.nanoTime())

private enum class TopLevel(val label: String, val icon: ImageVector, val route: Any) {
    TODAY("Today", Icons.Filled.Home, TodayRoute),
    CALENDAR("Calendar", Icons.Filled.DateRange, CalendarRoute),
    SETTINGS("Settings", Icons.Filled.Settings, SettingsRoute),
}

@Composable
fun AppNavHost(
    container: AppContainer,
    openRequest: StateFlow<OpenRequest?>,
    onOpened: (OpenRequest) -> Unit,
    sayTaskRequest: StateFlow<Long?>,
    onSayTaskOpened: (Long) -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val request by openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val open = request ?: return@LaunchedEffect
        navController.navigate(TaskRoute(open.occurrenceId, open.confirmMove)) { launchSingleTop = true }
        onOpened(open)
    }
    val sayTask by sayTaskRequest.collectAsStateWithLifecycle()
    LaunchedEffect(sayTask) {
        val say = sayTask ?: return@LaunchedEffect
        navController.navigate(QuickAddRoute(listen = true)) { launchSingleTop = true }
        onSayTaskOpened(say)
    }

    // Read again on every resume: the 12/24-hour setting and notification access change outside the app.
    var resumes by remember { mutableIntStateOf(0) }
    var notificationsAllowed by remember { mutableStateOf(container.abilities.notificationsAllowed()) }
    LifecycleResumeEffect(Unit) {
        resumes++
        notificationsAllowed = container.abilities.notificationsAllowed()
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = container.abilities.notificationsAllowed()
        val activity = context as? Activity
        // Refused twice, Android stops asking and refuses at once: the app's notification settings are the way left.
        if (!granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            context.openSettings(container.abilities.notificationSettings())
        }
        container.appScope.launch { container.service.reconcile() }
    }
    val fixNotifications = {
        if (container.abilities.needsNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.openSettings(container.abilities.notificationSettings())
        }
    }
    // Both calendar permissions at once (one prompt). Refused twice, Android stops asking.
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val activity = context as? Activity
        val refused = granted.values.any { !it }
        if (refused && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)) {
            context.openSettings(container.abilities.appDetails())
        }
        if (!refused) container.calendarSync.requestSync()
    }
    val fixCalendar = { calendarPermission.launch(AndroidCalendarProvider.PERMISSIONS) }
    // A first task asks for notifications; nothing nudges without them.
    val askForNotifications = {
        if (container.abilities.needsNotificationPermission()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    CompositionLocalProvider(LocalFormats provides Formats(container.formats, resumes)) {
        NavHost(navController = navController, startDestination = TodayRoute) {
            composable<TodayRoute> {
                TodayScreen(
                    viewModel = viewModel {
                        TodayViewModel(container.store, container.service, container.settings, container.time, container.formats)
                    },
                    notificationsAllowed = notificationsAllowed,
                    bottomBar = { TopLevelBar(TopLevel.TODAY, navController) },
                    onOpen = { navController.navigate(TaskRoute(it)) },
                    onAdd = { navController.navigate(EditRoute()) },
                    onSay = { navController.navigate(QuickAddRoute(listen = true)) },
                    onFixNotifications = fixNotifications,
                )
            }
            composable<CalendarRoute> {
                CalendarScreen(
                    viewModel = viewModel { CalendarViewModel(createSavedStateHandle(), container.store, container.time, container.formats) },
                    bottomBar = { TopLevelBar(TopLevel.CALENDAR, navController) },
                    onOpenOccurrence = { navController.navigate(TaskRoute(it)) },
                    onOpenTask = { navController.navigate(EditRoute(taskId = it)) },
                    onAdd = { day -> navController.navigate(EditRoute(day = day?.toString())) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    viewModel = viewModel {
                        SettingsViewModel(container.settings, container.service, container.abilities::current, container.calendarProvider, container.calendarSync)
                    },
                    versionName = context.versionName(),
                    bottomBar = { TopLevelBar(TopLevel.SETTINGS, navController) },
                    onFixNotifications = fixNotifications,
                    onFixExactAlarms = { context.openSettings(container.abilities.exactAlarmSettings()) },
                    onFixFullScreen = { context.openSettings(container.abilities.fullScreenSettings()) },
                    onFixBattery = { context.openSettings(container.abilities.batterySettings()) },
                    onFixCalendar = fixCalendar,
                )
            }
            composable<TaskRoute> { entry ->
                val route = entry.toRoute<TaskRoute>()
                TaskScreen(
                    viewModel = viewModel {
                        TaskViewModel(
                            occurrenceId = route.occurrenceId,
                            confirmMove = route.confirmMove,
                            handle = createSavedStateHandle(),
                            store = container.store,
                            service = container.service,
                            settings = container.settings,
                            time = container.time,
                            formats = container.formats,
                        )
                    },
                    onBack = { navController.backOrToday() },
                    onEdit = { navController.navigate(EditRoute(taskId = it)) },
                )
            }
            composable<EditRoute> { entry ->
                val route = entry.toRoute<EditRoute>()
                EditTaskScreen(
                    viewModel = viewModel {
                        EditTaskViewModel(
                            taskId = route.taskId,
                            initialDay = route.day?.let(LocalDate::parse),
                            handle = createSavedStateHandle(),
                            store = container.store,
                            service = container.service,
                            settings = container.settings,
                            time = container.time,
                        )
                    },
                    onClose = { navController.backOrToday() },
                    onSaved = { added ->
                        navController.backOrToday()
                        // Notifications are asked for when the first task is added, not at launch.
                        if (added) askForNotifications()
                    },
                )
            }
            composable<QuickAddRoute> { entry ->
                val route = entry.toRoute<QuickAddRoute>()
                QuickAddScreen(
                    viewModel = viewModel {
                        QuickAddViewModel(
                            handle = createSavedStateHandle(),
                            listen = route.listen,
                            service = container.service,
                            settings = container.settings,
                            time = container.time,
                            calendarSync = container.calendarSync,
                            provider = container.calendarProvider,
                        )
                    },
                    onClose = { navController.backOrToday() },
                    onAdded = { title, inCalendar ->
                        navController.backOrToday()
                        askForNotifications()
                        if (inCalendar == false) {
                            android.widget.Toast.makeText(context, "“$title” is added here, but Google Calendar refused it.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TopLevelBar(current: TopLevel, navController: NavHostController) {
    NavigationBar {
        TopLevel.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = {
                    if (destination != current) {
                        navController.navigate(destination.route) {
                            popUpTo(TodayRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

/** Back, or to Today when the screen was opened straight from a notification. */
private fun NavHostController.backOrToday() {
    if (!popBackStack()) navigate(TodayRoute) { launchSingleTop = true }
}

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Some phones lack the specific page; the app's own details page always exists.
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun Context.versionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
