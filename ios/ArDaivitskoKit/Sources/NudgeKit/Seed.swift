import Foundation

/// The tasks a UI test starts the app with (read from ARDAIVITSKO_SEED in Debug builds only). Days
/// and moments are given relative to the launch, so a test can put a nudge a minute away or a task
/// three days overdue whenever it runs.
public struct Seed: Codable, Sendable {
    public static let currentFormat = 1
    public static let environmentKey = "ARDAIVITSKO_SEED"

    /// Always present, so `{}` isn't taken for "no tasks": an empty seed must say so.
    public var format: Int
    public var tasks: [Entry]
    /// The settings to start with; standard ones when missing.
    public var settings: NudgeSettings?
    /// Seconds from launch to what the previous refresh covered, to test catch-ups; none by default.
    public var coveredUntilOffset: TimeInterval?

    public init(tasks: [Entry], settings: NudgeSettings? = nil, coveredUntilOffset: TimeInterval? = nil) {
        format = Self.currentFormat
        self.tasks = tasks
        self.settings = settings
        self.coveredUntilOffset = coveredUntilOffset
    }

    public struct Entry: Codable, Sendable {
        public var title: String
        public var note: String?
        /// Days from the launch day: 0 today, -2 two days ago.
        public var dayOffset: Int
        public var rule: NudgeRule?
        public var repeats: Repeat?
        /// Seconds from launch to the task's creation; a day before launch when missing.
        public var createdOffset: TimeInterval?
        /// The state of its first occurrence.
        public var state: OccurrenceState?
        public var inProgressOffset: TimeInterval?
        public var snoozedOffset: TimeInterval?

        public init(
            title: String, note: String? = nil, dayOffset: Int = 0, rule: NudgeRule? = nil,
            repeats: Repeat? = nil, createdOffset: TimeInterval? = nil, state: OccurrenceState? = nil,
            inProgressOffset: TimeInterval? = nil, snoozedOffset: TimeInterval? = nil
        ) {
            self.title = title
            self.note = note
            self.dayOffset = dayOffset
            self.rule = rule
            self.repeats = repeats
            self.createdOffset = createdOffset
            self.state = state
            self.inProgressOffset = inProgressOffset
            self.snoozedOffset = snoozedOffset
        }
    }

    /// Reads a seed, refusing one of another format.
    public static func decode(_ json: String) throws -> Seed {
        let seed = try JSONDecoder().decode(Seed.self, from: Data(json.utf8))
        guard seed.format == currentFormat else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Seed format \(seed.format) isn't \(currentFormat)"))
        }
        return seed
    }

    public func encoded() throws -> String {
        String(decoding: try JSONEncoder().encode(self), as: UTF8.self)
    }
}
