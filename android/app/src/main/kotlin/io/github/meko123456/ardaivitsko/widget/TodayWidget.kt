package io.github.meko123456.ardaivitsko.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.meko123456.ardaivitsko.MainActivity
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.nudge.Intents
import io.github.meko123456.ardaivitsko.nudge.WidgetRefresher
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One row of the widget: a task still open today, or overdue. */
data class WidgetRow(val occurrenceId: Long, val title: String, val line: String, val overdue: Boolean)

/**
 * Today's open tasks on the Home Screen, overdue first, each with a Done button. Glance keeps a
 * session open after each update and recomposes it instead of calling provideGlance again, so the
 * rows are read inside the composition, keyed on a version that every refresh bumps.
 */
class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version = currentState<Preferences>()[VERSION] ?: 0
            val rows by produceState<List<WidgetRow>?>(initialValue = null, version) { value = loadRows(context) }
            GlanceTheme { WidgetContent(rows) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { WidgetContent(PREVIEW_ROWS) } }
    }

    companion object {
        val VERSION = intPreferencesKey("version")

        /** Made-up rows for the widget picker, never the user's own tasks. */
        val PREVIEW_ROWS = listOf(
            WidgetRow(1, "Pay the credit card bill", "Overdue since 28 Oct", overdue = true),
            WidgetRow(2, "Call the bank", "Due today by 18:00", overdue = false),
            WidgetRow(3, "Buy medicine", "Due today by 23:59", overdue = false),
        )

        suspend fun loadRows(context: Context): List<WidgetRow> {
            val container = context.appContainer
            val today = container.time.today()
            val texts = container.formats.texts(container.time.zone())
            return container.store.observeActive(today).first()
                .filter { !it.isClosed }
                .sortedWith(compareBy({ it.day >= today }, { it.day }, { it.task.deadline }))
                .map { WidgetRow(it.id, it.task.title, texts.dueLine(it.day, it.task.deadline, today), overdue = it.day < today) }
        }

        suspend fun refreshAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            for (id in manager.getGlanceIds(TodayWidget::class.java)) {
                updateAppWidgetState(context, id) { it[VERSION] = (it[VERSION] ?: 0) + 1 }
                TodayWidget().update(context, id)
            }
        }
    }
}

@Composable
private fun WidgetContent(rows: List<WidgetRow>?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        Text(
            "Today",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        )
        Spacer(GlanceModifier.height(8.dp))
        when {
            rows == null -> Unit
            rows.isEmpty() -> Text(
                "Nothing left for today.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
            )
            else -> LazyColumn {
                items(rows, itemId = { it.occurrenceId }) { row -> WidgetRowView(row) }
            }
        }
    }
}

@Composable
private fun WidgetRowView(row: WidgetRow) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(Intents.openIntent(LocalContext.current, row.occurrenceId))),
        ) {
            Text(row.title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 2)
            Text(
                row.line,
                style = TextStyle(color = if (row.overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Button(
            text = "Done",
            onClick = actionRunCallback<MarkDoneAction>(actionParametersOf(OCCURRENCE to row.occurrenceId)),
            colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.primary, contentColor = GlanceTheme.colors.onPrimary),
        )
    }
}

val OCCURRENCE = ActionParameters.Key<Long>("occurrence")

/** The widget's Done: the same answer as the notification's, which also refreshes the widget. */
class MarkDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val occurrenceId = parameters[OCCURRENCE] ?: return
        context.appContainer.service.answer(occurrenceId, Answer.Done)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class GlanceWidgetRefresher(private val context: Context, private val scope: CoroutineScope) : WidgetRefresher {
    override fun refresh() {
        scope.launch { TodayWidget.refreshAll(context) }
    }
}

/** Publishes the picker's generated preview (Android 15+), once per install: the call is rate-limited. */
suspend fun publishWidgetPreview(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val prefs = context.getSharedPreferences("widget_preview", Context.MODE_PRIVATE)
    val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    if (prefs.getLong("published_for", 0L) == installed) return
    val result = GlanceAppWidgetManager(context).setWidgetPreviews(TodayWidgetReceiver::class)
    if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
        prefs.edit { putLong("published_for", installed) }
    }
}
