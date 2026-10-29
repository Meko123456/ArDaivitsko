package io.github.meko123456.ardaivitsko.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.core.NUDGE_INTERVALS
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.time.TimeSource
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Everything the form holds; a task's fields from docs/behaviour.md. */
data class TaskForm(
    val title: String = "",
    val note: String = "",
    val day: LocalDate,
    val deadline: LocalTime = TaskDefaults.DEADLINE,
    val cantMiss: Boolean = false,
    val repeat: Repeat = Repeat.NEVER,
    val startTime: LocalTime = TaskDefaults.START,
    val intervalHours: Int = TaskDefaults.INTERVAL_HOURS,
) {
    val canSave: Boolean get() = title.isNotBlank() && intervalHours in NUDGE_INTERVALS

    /** Written to the SavedStateHandle as plain strings, so it survives process death. */
    fun saveTo(handle: SavedStateHandle) {
        handle[KEY_TITLE] = title
        handle[KEY_NOTE] = note
        handle[KEY_DAY] = day.toString()
        handle[KEY_DEADLINE] = deadline.toString()
        handle[KEY_CANT_MISS] = cantMiss
        handle[KEY_REPEAT] = repeat.name
        handle[KEY_START] = startTime.toString()
        handle[KEY_INTERVAL] = intervalHours
    }

    companion object {
        private const val KEY_TITLE = "form_title"
        private const val KEY_NOTE = "form_note"
        private const val KEY_DAY = "form_day"
        private const val KEY_DEADLINE = "form_deadline"
        private const val KEY_CANT_MISS = "form_cant_miss"
        private const val KEY_REPEAT = "form_repeat"
        private const val KEY_START = "form_start"
        private const val KEY_INTERVAL = "form_interval"

        /** The form as it was, or null on a first opening. What was typed wins over what's stored. */
        fun restore(handle: SavedStateHandle): TaskForm? {
            val day = handle.get<String>(KEY_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            fun time(key: String, default: LocalTime) = handle.get<String>(key)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: default
            return TaskForm(
                title = handle[KEY_TITLE] ?: "",
                note = handle[KEY_NOTE] ?: "",
                day = day,
                deadline = time(KEY_DEADLINE, TaskDefaults.DEADLINE),
                cantMiss = handle[KEY_CANT_MISS] ?: false,
                repeat = handle.get<String>(KEY_REPEAT)?.let { name -> Repeat.entries.firstOrNull { it.name == name } } ?: Repeat.NEVER,
                startTime = time(KEY_START, TaskDefaults.START),
                intervalHours = handle.get<Int>(KEY_INTERVAL)?.takeIf { it in NUDGE_INTERVALS } ?: TaskDefaults.INTERVAL_HOURS,
            )
        }

        fun of(task: Task) = TaskForm(
            title = task.title,
            note = task.note,
            day = task.day,
            deadline = task.deadline,
            cantMiss = task.cantMiss,
            repeat = task.repeat,
            startTime = task.startTime,
            intervalHours = task.intervalHours,
        )

        fun new(defaults: TaskDefaults, day: LocalDate) = TaskForm(
            day = day,
            deadline = defaults.deadline,
            startTime = defaults.startTime,
            intervalHours = defaults.intervalHours,
        )
    }
}

sealed interface EditEvent {
    data class Saved(val added: Boolean) : EditEvent
}

/**
 * Add or edit a task. A new one starts from the defaults in Settings, on [initialDay] or today; an
 * edited one from what's stored. Every change is written to the SavedStateHandle at once.
 */
class EditTaskViewModel(
    private val taskId: Long,
    private val initialDay: LocalDate?,
    private val handle: SavedStateHandle,
    private val store: TaskStore,
    private val service: NudgeService,
    private val settings: SettingsStore,
    private val time: TimeSource,
) : ViewModel() {

    val isNew: Boolean get() = taskId == 0L

    /**
     * Snapshot state rather than a flow: a text field must see its new value in the same frame,
     * or fast typing drops characters.
     */
    var form: TaskForm? by mutableStateOf(TaskForm.restore(handle))
        private set

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val events = Channel<EditEvent>(Channel.BUFFERED)
    val eventFlow: Flow<EditEvent> = events.receiveAsFlow()

    init {
        if (form == null) {
            viewModelScope.launch {
                val stored = if (isNew) null else store.task(taskId)
                set(stored?.let(TaskForm::of) ?: TaskForm.new(settings.current().defaults, initialDay ?: time.today()))
            }
        }
    }

    fun update(transform: (TaskForm) -> TaskForm) {
        form?.let { set(transform(it)) }
    }

    fun save() {
        val form = form ?: return
        if (!form.canSave || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            if (isNew) {
                service.addTask(form.toTask(id = 0, createdAt = time.now()))
            } else {
                val stored = store.task(taskId)
                if (stored != null) service.updateTask(form.toTask(id = taskId, createdAt = stored.createdAt))
            }
            events.send(EditEvent.Saved(added = isNew))
        }
    }

    private fun set(value: TaskForm) {
        form = value
        value.saveTo(handle)
    }

    private fun TaskForm.toTask(id: Long, createdAt: Instant) = Task(
        id = id,
        title = title.trim(),
        note = note.trim(),
        day = day,
        deadline = deadline,
        cantMiss = cantMiss,
        repeat = repeat,
        startTime = startTime,
        intervalHours = intervalHours,
        createdAt = createdAt,
    )
}
