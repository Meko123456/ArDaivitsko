import NudgeKit
import Nudger
import SwiftData
import SwiftUI
import TaskStore

/// One occurrence: where it stands, the four answers, the nudges still to come, and the task's
/// edit and delete.
struct TaskDetailView: View {
    @Environment(Nudger.self) private var nudger
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \TaskModel.createdAt) private var tasks: [TaskModel]
    let ref: String
    let now: Date
    @State private var editor: EditorRequest?
    @State private var confirmingDelete = false

    var body: some View {
        let snapshots = tasks.map(\.snapshot)
        if let occurrence = Agenda.occurrence(ref, in: snapshots),
           let task = snapshots.first(where: { $0.id == occurrence.taskID }) {
            details(occurrence, of: task)
        } else {
            ContentUnavailableView("This task is gone", systemImage: "checklist")
        }
    }

    private func details(_ occurrence: Occurrence, of task: TaskSnapshot) -> some View {
        let today = Day(now, in: nudger.calendar)
        let wording = nudger.wording
        let draft = task.spec.draft
        return List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(occurrence.title)
                        .font(.title2.weight(.semibold))
                    if !draft.note.isEmpty {
                        Text(draft.note)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                LabeledContent("State") {
                    Text(occurrence.isOverdue(today: today) ? "\(wording.overdueSince(occurrence)) · \(wording.state(of: occurrence))" : wording.state(of: occurrence))
                        .foregroundStyle(occurrence.isOverdue(today: today) ? .overdue : .secondary)
                }
                LabeledContent("Day", value: wording.relativeDay(occurrence.day, today: today) == wording.day(occurrence.day)
                    ? wording.longDay(occurrence.day)
                    : "\(wording.relativeDay(occurrence.day, today: today)), \(wording.day(occurrence.day))")
                LabeledContent("Deadline", value: wording.time(occurrence.rule.deadline))
                if occurrence.rule.cantMiss {
                    Label("Can't miss", systemImage: "exclamationmark.circle.fill")
                        .foregroundStyle(.ardaivitsko)
                }
                LabeledContent("Nudges", value: Self.cadence(draft, wording: wording))
                if draft.repeats != .never {
                    LabeledContent("Repeats", value: Self.repeatName(draft.repeats))
                }
            }

            Section("Answer") {
                AnswerButtons(occurrence: occurrence, today: today)
            }

            Section("Coming up") {
                let upcoming = Array(nudger.planner.nudges(for: occurrence, from: now, through: now.addingTimeInterval(2 * 86_400)).filter { $0.date > now }.prefix(8))
                if upcoming.isEmpty {
                    Text(occurrence.state == .done ? "No more nudges: it's done." : "No nudges in the next two days.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(upcoming, id: \.date) { nudge in
                        LabeledContent(wording.name(of: nudge.kind), value: wording.when(nudge.date, today: today))
                    }
                }
            }

            Section {
                Button {
                    if let model = nudger.store.task(task.id) {
                        editor = .edit(model)
                    }
                } label: {
                    Label("Edit Task", systemImage: "pencil")
                }
                Button(role: .destructive) {
                    confirmingDelete = true
                } label: {
                    Label("Delete Task", systemImage: "trash")
                }
            } footer: {
                if draft.repeats != .never {
                    Text("Deleting a repeating task deletes every day of it.")
                }
            }
        }
        .navigationTitle(occurrence.title)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editor) { request in
            TaskEditor(request: request)
        }
        // An alert, not a confirmation dialog: its buttons are dependable under VoiceOver and tests.
        .alert("Delete \(occurrence.title)?", isPresented: $confirmingDelete) {
            Button("Delete", role: .destructive) {
                Task {
                    if let model = nudger.store.task(task.id) {
                        await nudger.delete(model)
                    }
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Its nudges stop.")
        }
        .modifier(UndoMoveOffer())
    }

    static func cadence(_ draft: TaskDraft, wording: Wording) -> String {
        let every = draft.rule.interval == 1 ? "every hour" : "every \(draft.rule.interval) hours"
        return "From \(wording.time(draft.rule.start)), \(every)"
    }

    static func repeatName(_ repeats: Repeat) -> String {
        switch repeats {
        case .never: "Never"
        case .daily: "Every day"
        case .weekly: "Every week"
        case .monthly: "Every month"
        }
    }
}
