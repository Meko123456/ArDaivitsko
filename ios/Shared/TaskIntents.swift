import AppIntents
import Nudger
import NudgeKit

// Built into the app and the widget extension: the widget needs the type for its buttons, and as
// a Live Activity intent it always runs in the app's process, where the store is written and the
// notifications and alarms are kept.

/// Marks one occurrence Done: the widget's buttons, and the Can't-miss alarm's Done button. It
/// names the occurrence by a string (a saved id, or task@day for a repeat not saved yet): an
/// enum or entity parameter arrived garbled from an ad-hoc signed widget in the simulator.
struct CompleteTaskIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Mark a Task Done"
    static let description = IntentDescription("Marks one of today's tasks done, which stops its nudges.")
    static var isDiscoverable: Bool { false }

    @Parameter(title: "Task")
    var occurrenceID: String

    init() {}

    init(occurrenceID: String) {
        self.occurrenceID = occurrenceID
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        await Nudger.shared.answer(.done, ref: occurrenceID, from: .widget)
        return .result()
    }
}
