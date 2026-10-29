import NudgeKit
import Nudger
import SwiftUI
import TaskStore

/// What the editor opens with: a new task's defaults, or a saved task.
struct EditorRequest: Identifiable {
    let id = UUID()
    let taskID: String?
    let draft: TaskDraft

    static func new(on day: Day, settings: NudgeSettings) -> EditorRequest {
        EditorRequest(taskID: nil, draft: TaskDraft(title: "", day: day, rule: settings.defaults))
    }

    @MainActor
    static func edit(_ task: TaskModel) -> EditorRequest {
        EditorRequest(taskID: task.id.uuidString, draft: task.draft)
    }
}

/// A task's fields: title and note, its day and deadline, Can't miss, the repeat, and when and how
/// often it nudges.
struct TaskEditor: View {
    @Environment(Nudger.self) private var nudger
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    let request: EditorRequest
    @State private var draft: TaskDraft
    @State private var saving = false
    @FocusState private var titleFocused: Bool

    init(request: EditorRequest) {
        self.request = request
        _draft = State(initialValue: request.draft)
    }

    private var isNew: Bool { request.taskID == nil }
    private var calendar: Calendar { nudger.calendar }
    private var today: Day { nudger.today }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Title", text: $draft.title, axis: .vertical)
                        .focused($titleFocused)
                        .accessibilityIdentifier("Title")
                    TextField("Note", text: $draft.note, axis: .vertical)
                        .lineLimit(1...5)
                        .accessibilityIdentifier("Note")
                }

                Section {
                    DatePicker("Day", selection: dayBinding, in: earliestDay..., displayedComponents: .date)
                    DatePicker("Deadline", selection: timeBinding(\.rule.deadline), displayedComponents: .hourAndMinute)
                    Toggle("Can't miss", isOn: $draft.rule.cantMiss)
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if draft.rule.cantMiss {
                            Text("A heads-up at \(nudger.wording.time(NudgePlanner.headsUpTime)) the evening before, a nudge every half hour in the last three hours, an alarm in the last hour, and Deadline passed. Quiet hours don't silence these.")
                        }
                        if deadlinePassed {
                            Text("This deadline has already passed today.")
                                .foregroundStyle(.overdue)
                        }
                    }
                }

                Section {
                    picker("Repeat", selection: $draft.repeats) {
                        ForEach(Repeat.allCases, id: \.self) { repeats in
                            Text(TaskDetailView.repeatName(repeats)).tag(repeats)
                        }
                    }
                    DatePicker("Nudging starts", selection: timeBinding(\.rule.start), displayedComponents: .hourAndMinute)
                    picker("How often", selection: $draft.rule.interval) {
                        ForEach(NudgeRule.intervals, id: \.self) { hours in
                            Text(hours == 1 ? "Every hour" : "Every \(hours) hours").tag(hours)
                        }
                    }
                } footer: {
                    Text("It nudges from this time on its day until you answer: half as often once it's in progress, and every day after its day until it's answered.")
                }
            }
            .environment(\.calendar, calendar)
            .environment(\.timeZone, calendar.timeZone)
            .navigationTitle(isNew ? "New Task" : "Edit Task")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(trimmedTitle.isEmpty || saving)
                }
            }
            .onAppear {
                if isNew { titleFocused = true }
            }
        }
    }

    /// A menu picker draws its value in a fixed-height button that clips at the largest sizes,
    /// so those get a list to pick from.
    @ViewBuilder
    private func picker<Value: Hashable, Content: View>(
        _ title: String, selection: Binding<Value>, @ViewBuilder content: () -> Content
    ) -> some View {
        if typeSize.isAccessibilitySize {
            Picker(title, selection: selection, content: content)
                .pickerStyle(.navigationLink)
        } else {
            Picker(title, selection: selection, content: content)
                .pickerStyle(.menu)
        }
    }

    private var trimmedTitle: String {
        draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A new task can't be for a day already gone; an edited one keeps its day if it's earlier.
    private var earliestDay: Date {
        min(request.draft.day, today).date(at: .midnight, in: calendar)
    }

    private var deadlinePassed: Bool {
        draft.day == today && draft.day.date(at: draft.rule.deadline, in: calendar) < nudger.now
    }

    private var dayBinding: Binding<Date> {
        Binding(
            get: { draft.day.date(at: TimeOfDay(hour: 12, minute: 0), in: calendar) },
            set: { draft.day = Day($0, in: calendar) }
        )
    }

    private func timeBinding(_ keyPath: WritableKeyPath<TaskDraft, TimeOfDay>) -> Binding<Date> {
        Binding(
            get: { today.date(at: draft[keyPath: keyPath], in: calendar) },
            set: { date in
                let time = TimeOfDay(date, in: calendar)
                draft[keyPath: keyPath] = TimeOfDay(hour: time.hour, minute: time.minute)
            }
        )
    }

    private func save() {
        saving = true
        var finished = draft
        finished.title = trimmedTitle
        finished.note = draft.note.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            if let id = request.taskID, let task = nudger.store.task(id) {
                await nudger.update(task, to: finished)
            } else {
                await nudger.add(finished)
            }
            dismiss()
        }
    }
}
