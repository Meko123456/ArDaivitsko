import NudgeKit
import Nudger
import SwiftData
import SwiftUI
import TaskStore

/// What to answer today: anything overdue first, oldest first, then today's tasks, each with
/// where it stands and when it nudges next.
struct TodayView: View {
    @Environment(Nudger.self) private var nudger
    @Query(sort: \TaskModel.createdAt) private var tasks: [TaskModel]
    let now: Date
    @State private var editor: EditorRequest?

    var body: some View {
        let today = Day(now, in: nudger.calendar)
        let list = Agenda.today(today, in: tasks.map(\.snapshot))
        List {
            if nudger.permissions?.authorization == .denied {
                NotificationsOffNotice()
            }
            if !list.overdue.isEmpty {
                Section("Overdue") {
                    ForEach(list.overdue) { occurrence in
                        OccurrenceRow(occurrence: occurrence, today: today, now: now)
                    }
                }
            }
            Section {
                if list.today.isEmpty {
                    NothingToday { editor = .new(on: today, settings: nudger.settings.settings) }
                } else {
                    ForEach(list.today) { occurrence in
                        OccurrenceRow(occurrence: occurrence, today: today, now: now)
                    }
                }
            } header: {
                Text(nudger.wording.longDay(today))
            }
        }
        .navigationTitle("Today")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editor = .new(on: today, settings: nudger.settings.settings)
                } label: {
                    Label("New Task", systemImage: "plus")
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

/// Opens an occurrence's detail.
struct OccurrenceLink: Hashable {
    let ref: String
}

private struct NothingToday: View {
    let add: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Nothing to answer today")
                .font(.headline)
            Text("Add a task, and it'll keep asking until you answer.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Button("Add a Task", action: add)
                .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }
}

/// One occurrence: a Done button, its title, where it stands, and its next nudge. Swipe or hold
/// for the other answers; tap for the detail.
struct OccurrenceRow: View {
    @Environment(Nudger.self) private var nudger
    @Environment(\.dynamicTypeSize) private var typeSize
    let occurrence: Occurrence
    let today: Day
    let now: Date

    private var isDone: Bool { occurrence.isAnswered }

    var body: some View {
        let status = statusLine
        let next = nextLine
        // At the largest sizes the Done button sits above the text, which then has the full width.
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 12))
        layout {
            DoneButton(occurrence: occurrence)
            NavigationLink(value: OccurrenceLink(ref: occurrence.id)) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(occurrence.title)
                            .strikethrough(isDone)
                            .foregroundStyle(isDone ? .secondary : .primary)
                        if occurrence.rule.cantMiss, !isDone {
                            Image(systemName: "exclamationmark.circle.fill")
                                .foregroundStyle(.ardaivitsko)
                                .accessibilityHidden(true)
                        }
                    }
                    Text(status)
                        .font(.subheadline)
                        .foregroundStyle(occurrence.isOverdue(today: today) ? .overdue : .secondary)
                    if let next {
                        Group {
                            if typeSize.isAccessibilitySize {
                                Text(next)
                            } else {
                                Label(next, systemImage: occurrence.isQuiet(today: today) ? "bell.slash" : "bell")
                            }
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    }
                }
            }
            .accessibilityLabel(occurrence.title)
            .accessibilityValue([occurrence.rule.cantMiss && !isDone ? "Can't miss" : nil, status, next].compactMap(\.self).joined(separator: ", "))
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if isDone {
                Button(occurrence.state == .skipped ? "Not Skipped" : "Not Done") { Task { await nudger.reopen(ref: occurrence.id) } }
                    .tint(.gray)
            } else {
                Button("Done") { answer(.done) }
                    .tint(.green)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if occurrence.isQuiet(today: today) {
                Button("Skip") { answer(.skip) }
                    .tint(.gray)
            } else {
                if occurrence.canMoveToTomorrow(today: today) {
                    Button("Not Today") { answer(.notToday) }
                        .tint(.orange)
                }
                if !occurrence.isAnswered {
                    Button("In Progress") { answer(.inProgress) }
                        .tint(.blue)
                }
            }
        }
        .contextMenu {
            AnswerButtons(occurrence: occurrence, today: today)
        }
    }

    private func answer(_ answer: Answer) {
        Task { await nudger.answer(answer, ref: occurrence.id) }
    }

    /// "Overdue since 28 Oct · In progress", "In progress · due by 23:59", "Done".
    private var statusLine: String {
        let wording = nudger.wording
        if occurrence.isOverdue(today: today) {
            return occurrence.state == .inProgress ? "\(wording.overdueSince(occurrence)) · In progress" : wording.overdueSince(occurrence)
        }
        if occurrence.isAnswered { return wording.state(of: occurrence) }
        let due = "due by \(wording.time(occurrence.rule.deadline))"
        let state = wording.state(of: occurrence)
        return occurrence.state == .open ? "Due by \(wording.time(occurrence.rule.deadline))" : "\(state) · \(due)"
    }

    private var nextLine: String? {
        if occurrence.isQuiet(today: today) { return nudger.wording.quietNote }
        guard !occurrence.isAnswered, let next = nudger.planner.nextNudge(for: occurrence, after: now) else { return nil }
        return nudger.wording.next(next, today: today)
    }
}

/// Marks an occurrence Done, or not done again.
struct DoneButton: View {
    @Environment(Nudger.self) private var nudger
    let occurrence: Occurrence

    var body: some View {
        let done = occurrence.state == .done
        let skipped = occurrence.state == .skipped
        Button {
            Task {
                if done || skipped {
                    await nudger.reopen(ref: occurrence.id)
                } else {
                    await nudger.answer(.done, ref: occurrence.id)
                }
            }
        } label: {
            Image(systemName: done ? "checkmark.circle.fill" : (skipped ? "minus.circle.fill" : "circle"))
                .font(.title2)
                .foregroundStyle(done ? Color.green : Color.secondary)
                .frame(minWidth: 28, minHeight: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(
            done ? "Mark \(occurrence.title) not done"
                : skipped ? "Mark \(occurrence.title) not skipped" : "Mark \(occurrence.title) done"
        )
    }
}

/// The answers, as menu or list buttons: Done, In progress, Not today, Snooze 1 hour.
struct AnswerButtons: View {
    @Environment(Nudger.self) private var nudger
    let occurrence: Occurrence
    let today: Day

    var body: some View {
        if occurrence.isAnswered {
            Button {
                Task { await nudger.reopen(ref: occurrence.id) }
            } label: {
                Label(occurrence.state == .skipped ? "Not Skipped After All" : "Not Done After All", systemImage: "arrow.uturn.backward")
            }
        } else if occurrence.isQuiet(today: today) {
            // A later day of this task is the one that nudges: this one can only be finished or set aside.
            Button {
                answer(.done)
            } label: {
                Label("Done", systemImage: "checkmark.circle")
            }
            Button {
                answer(.skip)
            } label: {
                Label("Skip", systemImage: "forward.end")
            }
        } else {
            Button {
                answer(.done)
            } label: {
                Label("Done", systemImage: "checkmark.circle")
            }
            Button {
                answer(.inProgress)
            } label: {
                Label(occurrence.state == .inProgress ? "Still in Progress" : "In Progress", systemImage: "hourglass")
            }
            if occurrence.canMoveToTomorrow(today: today) {
                Button {
                    answer(.notToday)
                } label: {
                    Label("Not Today", systemImage: "calendar.badge.clock")
                }
                Button {
                    answer(.snooze)
                } label: {
                    Label("Snooze 1 Hour", systemImage: "zzz")
                }
            }
        }
    }

    private func answer(_ answer: Answer) {
        Task { await nudger.answer(answer, ref: occurrence.id) }
    }
}

/// Without notifications nothing nudges: says so, and where to change it.
struct NotificationsOffNotice: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Label("Notifications are off", systemImage: "bell.slash")
                    .font(.headline)
                Text("Nothing can nudge you while you're in another app or the phone is locked.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Turn On in Settings") {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                        openURL(url)
                    }
                }
                .buttonStyle(.borderless)
            }
            .padding(.vertical, 4)
        }
    }
}
