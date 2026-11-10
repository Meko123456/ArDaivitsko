package io.github.meko123456.ardaivitsko.ui.quickadd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.calendar.CalendarProvider
import io.github.meko123456.ardaivitsko.calendar.CalendarSync
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.SpokenTasks
import io.github.meko123456.ardaivitsko.core.Task
import io.github.meko123456.ardaivitsko.core.TaskDefaults
import io.github.meko123456.ardaivitsko.data.SettingsStore
import io.github.meko123456.ardaivitsko.nudge.NudgeService
import io.github.meko123456.ardaivitsko.time.TimeSource
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** A task as the sentence describes it, with the parts the user set by hand pinned against the parser. */
data class QuickAddState(
    val sentence: String = "",
    val title: String = "",
    val day: LocalDate,
    val time: LocalTime? = null,
    val repeat: Repeat = Repeat.NEVER,
    /** Also write it to Google Calendar; only offered when Settings names a calendar. */
    val toCalendar: Boolean = false,
    /** The calendar spoken tasks go to, by name, or null when none is chosen in Settings. */
    val calendarName: String? = null,
    /** "No speech recognition on this phone", and the like. */
    val notice: String? = null,
    val adding: Boolean = false,
) {
    val canAdd: Boolean get() = title.isNotBlank() && !adding
}

sealed interface QuickAddEvent {
    /** [inCalendar] is null when the task wasn't meant for the calendar, false when the calendar refused it. */
    data class Added(val title: String, val inCalendar: Boolean?) : QuickAddEvent
}

/**
 * Say a task, or type it: the sentence is parsed as it changes (SpokenTasks), and the day, time
 * and repeat it found can each be changed by hand, after which the parser leaves that one alone.
 * Everything is kept in the SavedStateHandle, so it survives process death, as the edit form does.
 */
class QuickAddViewModel(
    private val handle: SavedStateHandle,
    listen: Boolean,
    private val service: NudgeService,
    private val settings: SettingsStore,
    private val time: TimeSource,
    private val calendarSync: CalendarSync,
    private val provider: CalendarProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    var state: QuickAddState by mutableStateOf(restore(handle, time.today()))
        private set

    private val events = Channel<QuickAddEvent>(Channel.BUFFERED)
    val eventFlow: Flow<QuickAddEvent> = events.receiveAsFlow()

    init {
        // Opened by the shortcut: listen once, not again after a rotation.
        if (!handle.contains(KEY_LISTEN)) handle[KEY_LISTEN] = listen
        viewModelScope.launch {
            val calendarId = settings.current().calendar.writeCalendarId ?: return@launch
            val name = withContext(io) {
                if (provider.hasAccess()) provider.calendars().firstOrNull { it.id == calendarId }?.name else null
            } ?: return@launch
            // Set up for the calendar: on by default, unless this screen already decided.
            set(state.copy(calendarName = name, toCalendar = handle[KEY_TO_CALENDAR] ?: true))
        }
    }

    /** Whether the screen should start the recognizer now; true once per opening. */
    fun takeListenRequest(): Boolean {
        val pending = handle.get<Boolean>(KEY_LISTEN) == true
        handle[KEY_LISTEN] = false
        return pending
    }

    /** The recognizer's words replace the sentence, and the parser rules again. */
    fun heard(text: String) {
        handle[KEY_PINS] = emptyList<String>()
        setSentence(text)
    }

    fun setSentence(text: String) {
        val parsed = SpokenTasks.parse(text, time.today())
        val pins = pins()
        set(
            state.copy(
                sentence = text,
                title = parsed.title,
                day = if (Pin.DAY in pins) state.day else parsed.day,
                time = if (Pin.TIME in pins) state.time else parsed.time,
                repeat = if (Pin.REPEAT in pins) state.repeat else parsed.repeat,
                notice = null,
            ),
        )
    }

    fun pickDay(day: LocalDate) {
        pin(Pin.DAY)
        set(state.copy(day = day))
    }

    fun pickTime(time: LocalTime?) {
        pin(Pin.TIME)
        set(state.copy(time = time))
    }

    fun pickRepeat(repeat: Repeat) {
        pin(Pin.REPEAT)
        set(state.copy(repeat = repeat))
    }

    fun setToCalendar(on: Boolean) {
        handle[KEY_TO_CALENDAR] = on
        set(state.copy(toCalendar = on))
    }

    fun noRecognizer() {
        set(state.copy(notice = "This phone has no speech recognition. Type the task instead."))
    }

    fun add() {
        val current = state
        if (!current.canAdd) return
        set(current.copy(adding = true))
        viewModelScope.launch {
            val defaults: TaskDefaults = settings.current().defaults
            val task = Task(
                title = current.title.trim(),
                day = current.day,
                deadline = current.time ?: defaults.deadline,
                repeat = current.repeat,
                startTime = defaults.startTime,
                intervalHours = defaults.intervalHours,
                createdAt = time.now(),
            )
            val id = service.addTask(task)
            val calendarId = settings.current().calendar.writeCalendarId
            val inCalendar = if (current.toCalendar && current.calendarName != null && calendarId != null) {
                withContext(io) { calendarSync.addEvent(id, calendarId) }
            } else {
                null
            }
            events.send(QuickAddEvent.Added(task.title, inCalendar))
        }
    }

    private enum class Pin { DAY, TIME, REPEAT }

    private fun pins(): Set<Pin> = handle.get<List<String>>(KEY_PINS).orEmpty().map(Pin::valueOf).toSet()

    private fun pin(pin: Pin) {
        handle[KEY_PINS] = (pins() + pin).map { it.name }
    }

    private fun set(value: QuickAddState) {
        state = value
        handle[KEY_SENTENCE] = value.sentence
        handle[KEY_TITLE] = value.title
        handle[KEY_DAY] = value.day.toString()
        handle[KEY_TIME] = value.time?.toString()
        handle[KEY_REPEAT] = value.repeat.name
    }

    private companion object {
        const val KEY_LISTEN = "quick_listen"
        const val KEY_SENTENCE = "quick_sentence"
        const val KEY_TITLE = "quick_title"
        const val KEY_DAY = "quick_day"
        const val KEY_TIME = "quick_time"
        const val KEY_REPEAT = "quick_repeat"
        const val KEY_PINS = "quick_pins"
        const val KEY_TO_CALENDAR = "quick_to_calendar"

        fun restore(handle: SavedStateHandle, today: LocalDate): QuickAddState = QuickAddState(
            sentence = handle[KEY_SENTENCE] ?: "",
            title = handle[KEY_TITLE] ?: "",
            day = handle.get<String>(KEY_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today,
            time = handle.get<String>(KEY_TIME)?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            repeat = handle.get<String>(KEY_REPEAT)?.let { name -> Repeat.entries.firstOrNull { it.name == name } } ?: Repeat.NEVER,
            toCalendar = handle[KEY_TO_CALENDAR] ?: false,
        )
    }
}
