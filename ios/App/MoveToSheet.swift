import NudgeKit
import Nudger
import SwiftUI

/// Move to…: a calendar from today on, opening on the day after the occurrence's own (counted from
/// today when it's overdue, where Not today would move it). The move gets the same Undo as Not today.
struct MoveToSheet: View {
    @Environment(Nudger.self) private var nudger
    @Environment(\.dismiss) private var dismiss
    let occurrence: Occurrence
    let today: Day
    @State private var day: Day

    init(occurrence: Occurrence, today: Day) {
        self.occurrence = occurrence
        self.today = today
        _day = State(initialValue: max(occurrence.day, today).adding(days: 1))
    }

    private var calendar: Calendar { nudger.calendar }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Day", selection: dayBinding, in: today.date(at: .midnight, in: calendar)..., displayedComponents: .date)
                        .datePickerStyle(.graphical)
                } footer: {
                    Text("Its deadline stays at \(nudger.wording.time(occurrence.rule.deadline)). On the new day it nudges like a new task.")
                }
            }
            .navigationTitle("Move to…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Move") {
                        let day = day
                        Task { await nudger.move(ref: occurrence.id, to: day) }
                        dismiss()
                    }
                    .disabled(day == occurrence.day)
                }
            }
        }
        .environment(\.calendar, calendar)
        .environment(\.timeZone, calendar.timeZone)
    }

    /// Noon, so a daylight-saving change can't tip the picked date into the day before.
    private var dayBinding: Binding<Date> {
        Binding(
            get: { day.date(at: TimeOfDay(hour: 12, minute: 0), in: calendar) },
            set: { day = Day($0, in: calendar) }
        )
    }
}
