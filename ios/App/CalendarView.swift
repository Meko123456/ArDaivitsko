import NudgeKit
import Nudger
import SwiftData
import SwiftUI
import TaskStore

/// A month at a glance: a mark on each day with tasks, filled while anything is unanswered and
/// hollow once all are done. Below it, the chosen day's tasks.
struct CalendarView: View {
    @Environment(Nudger.self) private var nudger
    @Query(sort: \TaskModel.createdAt) private var tasks: [TaskModel]
    let now: Date
    /// The first of the month shown; this month when nil.
    @State private var month: Day?
    @State private var selected: Day?
    @State private var editor: EditorRequest?

    var body: some View {
        let today = Day(now, in: nudger.calendar)
        let shown = month ?? Day(year: today.year, month: today.month, day: 1)
        let chosen = selected ?? today
        let snapshots = tasks.map(\.snapshot)
        let last = Day(year: shown.year, month: shown.month, day: Day.length(ofMonth: shown.month, in: shown.year))
        let marks = Agenda.marks(from: shown, through: last, in: snapshots)
        let occurrences = Agenda.occurrences(on: chosen, in: snapshots)

        List {
            Section {
                MonthGrid(month: shown, today: today, selected: chosen, marks: marks) { day in
                    selected = day
                }
                .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
            } header: {
                MonthHeader(month: shown) { months in
                    month = shown.addingMonths(months, dayOfMonth: 1)
                }
            }

            Section {
                if occurrences.isEmpty {
                    Text("Nothing on this day")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(occurrences) { occurrence in
                        OccurrenceRow(occurrence: occurrence, today: today, now: now)
                    }
                }
            } header: {
                Text(nudger.wording.longDay(chosen))
            }
        }
        .navigationTitle("Calendar")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editor = .new(on: max(chosen, today), settings: nudger.settings.settings)
                } label: {
                    Label("New Task", systemImage: "plus")
                }
            }
            if month != nil || selected != nil {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Today") {
                        month = nil
                        selected = nil
                    }
                }
            }
        }
        .navigationDestination(for: OccurrenceLink.self) { link in
            TaskDetailView(ref: link.ref, now: now)
        }
        .sheet(item: $editor) { request in
            TaskEditor(request: request)
        }
        .modifier(UndoMoveOffer())
    }
}

private struct MonthHeader: View {
    @Environment(Nudger.self) private var nudger
    let month: Day
    let move: (Int) -> Void

    var body: some View {
        HStack {
            Button {
                move(-1)
            } label: {
                Image(systemName: "chevron.left")
                    .padding(8)
            }
            .accessibilityLabel("Previous month")
            Spacer()
            Text(nudger.wording.month(month))
                .font(.headline)
                .foregroundStyle(.primary)
                .textCase(nil)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button {
                move(1)
            } label: {
                Image(systemName: "chevron.right")
                    .padding(8)
            }
            .accessibilityLabel("Next month")
        }
    }
}

/// Seven columns of days, starting on the locale's first weekday.
private struct MonthGrid: View {
    @Environment(Nudger.self) private var nudger
    let month: Day
    let today: Day
    let selected: Day
    let marks: [Day: Agenda.Mark]
    let choose: (Day) -> Void

    private var firstWeekday: Int { Calendar.autoupdatingCurrent.firstWeekday }

    /// One identity space for the whole grid: with three ForEach loops over plain numbers the
    /// weekday headers, the blanks and the first days shared ids, and the first week went missing.
    private enum Cell: Hashable, Identifiable {
        case weekday(Int, String)
        case blank(Int)
        case day(Day)

        var id: Self { self }
    }

    private var cells: [Cell] {
        let leading = (month.weekday - firstWeekday + 7) % 7
        let length = Day.length(ofMonth: month.month, in: month.year)
        return weekdaySymbols.enumerated().map { Cell.weekday($0.offset, $0.element) }
            + (0..<leading).map(Cell.blank)
            + (1...length).map { Cell.day(Day(year: month.year, month: month.month, day: $0)) }
    }

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 7)
        LazyVGrid(columns: columns, spacing: 6) {
            ForEach(cells) { cell in
                switch cell {
                case .weekday(_, let symbol):
                    Text(symbol)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                case .blank:
                    Color.clear.frame(height: 1)
                case .day(let day):
                    DayCell(day: day, isToday: day == today, isSelected: day == selected, mark: marks[day]) {
                        choose(day)
                    }
                }
            }
        }
        // The grid keeps seven columns; at the largest text sizes its numbers stop growing, and the
        // day's list below carries the full size.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
    }

    private var weekdaySymbols: [String] {
        let symbols = Calendar.autoupdatingCurrent.veryShortStandaloneWeekdaySymbols
        let start = firstWeekday - 1
        return Array(symbols[start...] + symbols[..<start])
    }
}

private struct DayCell: View {
    @Environment(Nudger.self) private var nudger
    let day: Day
    let isToday: Bool
    let isSelected: Bool
    let mark: Agenda.Mark?
    let choose: () -> Void

    var body: some View {
        Button(action: choose) {
            VStack(spacing: 3) {
                Text("\(day.day)")
                    .font(.body.monospacedDigit().weight(isToday ? .bold : .regular))
                    .foregroundStyle(isSelected ? Color.white : (isToday ? Color.ardaivitsko : Color.primary))
                    .frame(minWidth: 34, minHeight: 34)
                    .background {
                        if isSelected {
                            Circle().fill(Color.ardaivitsko)
                        }
                    }
                markView
                    .frame(width: 7, height: 7)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(nudger.wording.longDay(day))
        .accessibilityValue(markDescription)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder
    private var markView: some View {
        switch mark {
        case .open:
            Circle().fill(Color.ardaivitsko)
        case .allDone:
            Circle().strokeBorder(Color.secondary, lineWidth: 1.5)
        case nil:
            Color.clear
        }
    }

    private var markDescription: String {
        switch mark {
        case .open: isToday ? "Today, tasks to answer" : "Tasks to answer"
        case .allDone: isToday ? "Today, all done" : "All done"
        case nil: isToday ? "Today" : ""
        }
    }
}
