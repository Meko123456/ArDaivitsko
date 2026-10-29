package io.github.meko123456.ardaivitsko.ui.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.core.Repeats
import io.github.meko123456.ardaivitsko.data.OccurrenceItem
import io.github.meko123456.ardaivitsko.data.TaskSeries
import io.github.meko123456.ardaivitsko.data.TaskStore
import io.github.meko123456.ardaivitsko.time.TextFormats
import io.github.meko123456.ardaivitsko.time.TimeSource
import io.github.meko123456.ardaivitsko.time.today
import io.github.meko123456.ardaivitsko.ui.statusLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Filled while anything that day is still open; hollow when everything is done. */
enum class Mark { OPEN, ALL_DONE }

data class DayCell(val date: LocalDate, val mark: Mark?, val isToday: Boolean, val selected: Boolean)

data class CalendarItem(
    /** Null for a repeat further ahead than the occurrences written out so far. */
    val occurrenceId: Long?,
    val taskId: Long,
    val title: String,
    val line: String,
    val done: Boolean,
    val cantMiss: Boolean,
)

data class CalendarState(
    val title: String = "",
    val weekdays: List<String> = emptyList(),
    /** Six weeks of cells, null for the days of the months around. */
    val cells: List<DayCell?> = emptyList(),
    val selected: LocalDate? = null,
    val selectedTitle: String = "",
    val items: List<CalendarItem> = emptyList(),
)

/**
 * A month at a time. Days come from the stored occurrences (moves included), and repeats further
 * ahead than those from each task's rule, as open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val handle: SavedStateHandle,
    private val store: TaskStore,
    private val time: TimeSource,
    private val formats: TextFormats,
) : ViewModel() {

    private val month = handle.getStateFlow(KEY_MONTH, YearMonth.from(time.today()).toString())
    private val selected = handle.getStateFlow(KEY_SELECTED, time.today().toString())
    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<CalendarState> = combine(month, refreshes) { m, _ -> YearMonth.parse(m) }
        .flatMapLatest { month ->
            combine(store.observeBetween(month.atDay(1), month.atEndOfMonth()), store.observeSeries()) { stored, series ->
                Triple(month, stored, series)
            }
        }
        .combine(selected) { (month, stored, series), selected -> build(month, stored, series, LocalDate.parse(selected)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarState())

    fun refresh() {
        refreshes.value++
    }

    fun showMonth(offset: Long) {
        val next = YearMonth.parse(month.value).plusMonths(offset)
        handle[KEY_MONTH] = next.toString()
        val today = time.today()
        handle[KEY_SELECTED] = (if (YearMonth.from(today) == next) today else next.atDay(1)).toString()
    }

    fun select(date: LocalDate) {
        handle[KEY_SELECTED] = date.toString()
    }

    private fun build(month: YearMonth, stored: List<OccurrenceItem>, series: List<TaskSeries>, selected: LocalDate): CalendarState {
        val today = time.today()
        val texts = formats.texts(time.zone())
        val byDay = HashMap<LocalDate, MutableList<CalendarItem>>()
        for (item in stored) {
            byDay.getOrPut(item.day) { mutableListOf() } += CalendarItem(
                occurrenceId = item.id,
                taskId = item.task.id,
                title = item.task.title,
                line = statusLabel(item.status, formats) + " · " + texts.dueLine(item.day, item.task.deadline, today),
                done = item.isDone,
                cantMiss = item.task.cantMiss,
            )
        }
        for ((task, seriesFrom, through) in series) {
            if (task.repeat == Repeat.NEVER) continue
            val from = maxOf(month.atDay(1), seriesFrom, through?.plusDays(1) ?: task.day)
            for (day in Repeats.days(task.day, task.repeat, from, month.atEndOfMonth())) {
                byDay.getOrPut(day) { mutableListOf() } += CalendarItem(
                    occurrenceId = null,
                    taskId = task.id,
                    title = task.title,
                    line = "Repeats · " + texts.dueLine(day, task.deadline, today),
                    done = false,
                    cantMiss = task.cantMiss,
                )
            }
        }

        val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val cells = buildList<DayCell?> {
            repeat(lead) { add(null) }
            for (d in 1..month.lengthOfMonth()) {
                val date = month.atDay(d)
                val items = byDay[date].orEmpty()
                val mark = when {
                    items.isEmpty() -> null
                    items.any { !it.done } -> Mark.OPEN
                    else -> Mark.ALL_DONE
                }
                add(DayCell(date, mark, isToday = date == today, selected = date == selected))
            }
            while (size % 7 != 0) add(null)
        }
        return CalendarState(
            title = formats.month(month),
            weekdays = (0 until 7).map { DayOfWeek.of((firstDay.value - 1 + it) % 7 + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
            cells = cells,
            selected = selected,
            selectedTitle = formats.longDate(selected),
            items = byDay[selected].orEmpty().sortedWith(compareBy({ it.done }, { it.title.lowercase() })),
        )
    }

    private companion object {
        const val KEY_MONTH = "month"
        const val KEY_SELECTED = "selected"
    }
}
