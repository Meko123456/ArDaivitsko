package io.github.meko123456.ardaivitsko.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.core.NudgePlanner
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.time.TextFormats
import io.github.meko123456.ardaivitsko.time.TimeSource
import io.github.meko123456.ardaivitsko.ui.statusLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

data class TaskRow(
    val occurrenceId: Long,
    val title: String,
    val cantMiss: Boolean,
    val state: OccurrenceState,
    /** "Overdue since 28 Oct", "Due today by 23:59" */
    val dueLine: String,
    /** "In progress · Next nudge at 16:00" */
    val statusLine: String,
    val overdue: Boolean,
) {
    val done: Boolean get() = state == OccurrenceState.DONE
}

data class TodayState(
    val loading: Boolean = true,
    val date: String = "",
    val overdue: List<TaskRow> = emptyList(),
    val today: List<TaskRow> = emptyList(),
)

sealed interface TodayEvent {
    data class MarkedDone(val occurrenceId: Long, val title: String) : TodayEvent
}

/**
 * Today: what's overdue first (oldest first), then today's tasks, open ones before done ones.
 * Each row says when it's due, its state and when it nudges next; the clock ticks every 30
 * seconds, and a new day (or time zone) moves the list on by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val store: TaskStore,
    private val service: NudgeService,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val formats: TextFormats,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)
    private val events = Channel<TodayEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TodayEvent> = events.receiveAsFlow()

    private val clock: SharedFlow<Instant> = refreshes
        .flatMapLatest {
            flow {
                while (true) {
                    emit(time.now())
                    delay(TICK_MILLIS)
                }
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val state: StateFlow<TodayState> = clock
        .map { it.atZone(time.zone()).toLocalDate() }
        .distinctUntilChanged()
        .flatMapLatest { today -> store.observeActive(today).map { today to it } }
        .combine(clock) { (today, items), now -> Triple(today, items, now) }
        .combine(settings.settings) { (today, items, now), settings -> build(today, items, now, NudgePlanner(settings.nudgeSettings, time.zone())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    /** On resume: the 12/24-hour setting or the zone may have changed while away. */
    fun refresh() {
        refreshes.value++
    }

    fun markDone(row: TaskRow) {
        viewModelScope.launch {
            service.answer(row.occurrenceId, Answer.Done)
            events.send(TodayEvent.MarkedDone(row.occurrenceId, row.title))
        }
    }

    fun reopen(occurrenceId: Long) {
        viewModelScope.launch { service.answer(occurrenceId, Answer.Reopen) }
    }

    private fun build(today: LocalDate, items: List<OccurrenceItem>, now: Instant, planner: NudgePlanner): TodayState {
        val texts = formats.texts(time.zone())
        val rows = items.map { item ->
            val next = if (item.isDone) null else texts.nextNudge(planner.next(item.occurrence, now), now)
            TaskRow(
                occurrenceId = item.id,
                title = item.task.title,
                cantMiss = item.task.cantMiss,
                state = item.state,
                dueLine = texts.dueLine(item.day, item.task.deadline, today),
                statusLine = listOfNotNull(statusLabel(item.status, formats), next).joinToString(" · "),
                overdue = item.day < today && !item.isDone,
            ) to item
        }
        return TodayState(
            loading = false,
            date = formats.longDate(today),
            overdue = rows.filter { it.first.overdue }.sortedWith(compareBy({ it.second.day }, { it.second.task.deadline })).map { it.first },
            today = rows.filter { !it.first.overdue }
                .sortedWith(compareBy({ it.first.done }, { it.second.task.deadline }, { it.first.title.lowercase() }))
                .map { it.first },
        )
    }

    companion object {
        const val TICK_MILLIS = 30_000L
    }
}
