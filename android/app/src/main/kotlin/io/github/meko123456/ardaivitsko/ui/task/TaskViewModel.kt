package io.github.meko123456.ardaivitsko.ui.task

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.core.Answers
import io.github.meko123456.ardaivitsko.core.NudgePlanner
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.nudge.Answer
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.time.TextFormats
import io.github.meko123456.ardaivitsko.time.TimeSource
import io.github.meko123456.ardaivitsko.ui.repeatLabel
import io.github.meko123456.ardaivitsko.ui.statusLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

data class TaskState(
    val loading: Boolean = true,
    /** The task was deleted (from another screen, or a stale notification). */
    val missing: Boolean = false,
    val taskId: Long = 0,
    val title: String = "",
    val note: String = "",
    val cantMiss: Boolean = false,
    val repeat: String? = null,
    val state: OccurrenceState = OccurrenceState.OPEN,
    val status: String = "",
    val dueLine: String = "",
    val nextNudge: String? = null,
    val day: LocalDate? = null,
    val canMoveToTomorrow: Boolean = false,
    val canSnooze: Boolean = false,
    val canUndoMove: Boolean = false,
    /** "This is the last day. Move it anyway?" is showing. */
    val confirmingMove: Boolean = false,
    /** An earlier day of a repeating task, quiet while a later day nudges: it offers Done and Skip. */
    val quiet: Boolean = false,
) {
    /** Done or Skipped. */
    val done: Boolean get() = state.isClosed
}

sealed interface TaskEvent {
    data class Message(val text: String, val undoMove: Boolean = false) : TaskEvent
    data object Deleted : TaskEvent
}

/**
 * One occurrence, as a notification opens it: the three answers, plus Snooze 1 hour and Move to…,
 * and Undo after a move. Opened from a Can't-miss task's Not today ([confirmMove]), it asks first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val occurrenceId: Long,
    confirmMove: Boolean,
    private val handle: SavedStateHandle,
    private val store: TaskStore,
    private val service: NudgeService,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val formats: TextFormats,
) : ViewModel() {

    private val events = Channel<TaskEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TaskEvent> = events.receiveAsFlow()
    private val refreshes = MutableStateFlow(0)

    init {
        // Opened by Not today on its deadline day: ask once, not again after a rotation.
        if (confirmMove && handle.get<Boolean>(KEY_CONFIRM_HANDLED) != true) {
            handle[KEY_CONFIRM_HANDLED] = true
            handle[KEY_CONFIRMING] = true
        }
    }

    private val clock: Flow<Instant> = refreshes.flatMapLatest {
        flow {
            while (true) {
                emit(time.now())
                delay(TICK_MILLIS)
            }
        }
    }

    private val latestDays: Flow<Map<Long, LocalDate>> = clock
        .map { it.atZone(time.zone()).toLocalDate() }
        .distinctUntilChanged()
        .flatMapLatest { store.observeLatestDays(it) }

    val state: StateFlow<TaskState> = combine(
        store.observeOccurrence(occurrenceId),
        clock,
        settings.settings,
        handle.getStateFlow(KEY_CONFIRMING, false),
        latestDays,
    ) { item, now, settings, confirming, latest ->
        if (item == null) TaskState(loading = false, missing = true) else build(item, now, NudgePlanner(settings.nudgeSettings, time.zone()), confirming, latest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskState())

    fun refresh() {
        refreshes.value++
    }

    fun done() = act(Answer.Done) { "Done. Its nudges have stopped." }

    fun inProgress() = act(Answer.InProgress) { item ->
        "In progress: nudges every ${item.task.intervalHours * 2} hours from now"
    }

    fun snooze() = act(Answer.Snooze) { item ->
        val next = NudgePlanner(settings.current().nudgeSettings, time.zone()).next(item.occurrence, time.now())
        "Snoozed. " + formats.texts(time.zone()).nextNudge(next, time.now())
    }

    /** Not today: straight away, or after "This is the last day. Move it anyway?" on a Can't-miss deadline day. */
    fun notToday() {
        viewModelScope.launch {
            val item = store.occurrence(occurrenceId) ?: return@launch
            if (Answers.notTodayNeedsConfirmation(item.task, item.status, today())) {
                handle[KEY_CONFIRMING] = true
            } else {
                move(Answer.NotToday)
            }
        }
    }

    fun confirmMove() {
        handle[KEY_CONFIRMING] = false
        viewModelScope.launch { move(Answer.NotToday) }
    }

    fun dismissConfirm() {
        handle[KEY_CONFIRMING] = false
    }

    fun moveTo(day: LocalDate) {
        viewModelScope.launch { move(Answer.MoveTo(day)) }
    }

    fun undoMove() = act(Answer.UndoMove) { item -> "Back on ${formats.date(item.day)}" }

    fun reopen() = act(Answer.Reopen) { "Open again." }

    /** Skip: an earlier missed day that won't be done. It stays in the calendar, as skipped. */
    fun skip() = act(Answer.Skip) { "Skipped. It won't nudge or be listed again." }

    fun delete() {
        viewModelScope.launch {
            val item = store.occurrence(occurrenceId) ?: return@launch
            service.deleteTask(item.task.id)
            events.send(TaskEvent.Deleted)
        }
    }

    private suspend fun move(answer: Answer) {
        val moved = service.answer(occurrenceId, answer) ?: return
        if (moved.state != OccurrenceState.MOVED) return
        val text = if (moved.day == today().plusDays(1)) "Moved to tomorrow" else "Moved to ${formats.date(moved.day)}"
        events.send(TaskEvent.Message(text, undoMove = true))
    }

    private fun act(answer: Answer, message: suspend (OccurrenceItem) -> String) {
        viewModelScope.launch {
            val item = service.answer(occurrenceId, answer) ?: return@launch
            events.send(TaskEvent.Message(message(item)))
        }
    }

    private fun today(): LocalDate = time.now().atZone(time.zone()).toLocalDate()

    private fun build(item: OccurrenceItem, now: Instant, planner: NudgePlanner, confirming: Boolean, latestDays: Map<Long, LocalDate>): TaskState {
        val today = now.atZone(time.zone()).toLocalDate()
        val texts = formats.texts(time.zone())
        val quiet = item.isQuiet(latestDays)
        return TaskState(
            loading = false,
            taskId = item.task.id,
            title = item.task.title,
            note = item.task.note,
            cantMiss = item.task.cantMiss,
            repeat = repeatLabel(item.task),
            state = item.state,
            status = statusLabel(item.status, formats),
            dueLine = texts.dueLine(item.day, item.task.deadline, today),
            nextNudge = when {
                item.isClosed -> null
                quiet -> "No nudges: a later day of this task nudges instead"
                else -> texts.nextNudge(planner.next(item.occurrence, now), now)
            },
            day = item.day,
            canMoveToTomorrow = !quiet && Answers.canMoveToTomorrow(item.status, today),
            canSnooze = !quiet && !item.isClosed && item.day <= today,
            canUndoMove = !quiet && item.state == OccurrenceState.MOVED && item.status.movedFrom != null,
            confirmingMove = confirming && !item.isClosed && Answers.notTodayNeedsConfirmation(item.task, item.status, today),
            quiet = quiet,
        )
    }

    private companion object {
        const val KEY_CONFIRMING = "confirming_move"
        const val KEY_CONFIRM_HANDLED = "confirm_move_handled"
        const val TICK_MILLIS = 30_000L
    }
}
