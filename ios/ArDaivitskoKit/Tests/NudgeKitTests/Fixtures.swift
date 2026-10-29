import Foundation
@testable import NudgeKit

/// The worked examples' setting: Asia/Tbilisi, UTC+4 with no daylight saving.
let tbilisi = Calendar.gregorian(in: TimeZone(identifier: "Asia/Tbilisi")!)
let newYork = Calendar.gregorian(in: TimeZone(identifier: "America/New_York")!)

extension Day {
    init(_ year: Int, _ month: Int, _ day: Int) {
        self.init(year: year, month: month, day: day)
    }
}

extension TimeOfDay {
    init(_ hour: Int, _ minute: Int) {
        self.init(hour: hour, minute: minute)
    }
}

/// The moment the wall clock shows hour:minute on a day.
func at(_ day: Day, _ hour: Int, _ minute: Int, _ second: Int = 0, in calendar: Calendar = tbilisi) -> Date {
    day.date(at: TimeOfDay(hour: hour, minute: minute, second: second), in: calendar)
}

func rule(
    start: TimeOfDay = TimeOfDay(10, 0), every interval: Int = 3,
    deadline: TimeOfDay = TimeOfDay(23, 59), cantMiss: Bool = false
) -> NudgeRule {
    NudgeRule(start: start, interval: interval, deadline: deadline, cantMiss: cantMiss)
}

func occurrence(
    _ title: String = "Task", on day: Day, rule: NudgeRule = .standard, created: Date,
    state: OccurrenceState = .open, inProgressSince: Date? = nil, snoozedAt: Date? = nil, id: String = "o1"
) -> Occurrence {
    Occurrence(
        id: id, taskID: "t-\(id)", title: title, seriesDay: day, rule: rule, state: state,
        createdAt: created, inProgressSince: inProgressSince, snoozedAt: snoozedAt
    )
}

/// Nudges as "10-28 23:00 alarm", in the calendar's zone, so a failure reads like the spec.
func render(_ nudges: [Nudge], in calendar: Calendar = tbilisi) -> [String] {
    nudges.map { render($0.date, in: calendar) + " \($0.kind.rawValue)" }
}

func render(_ date: Date, in calendar: Calendar = tbilisi) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = calendar.timeZone
    formatter.dateFormat = "MM-dd HH:mm"
    return formatter.string(from: date)
}

/// The nudges an occurrence gives when answered at the given moments: before each answer what it
/// planned until then, and from the answer on what it plans after.
func history(
    of occurrence: Occurrence, answering answers: [(Answer, Date)], through end: Date, planner: NudgePlanner
) -> [Nudge] {
    var current = occurrence
    var from = occurrence.createdAt
    var nudges: [Nudge] = []
    for (answer, moment) in answers {
        nudges += planner.nudges(for: current, from: from, through: end).filter { $0.date < moment }
        current = current.answering(answer, at: moment, calendar: planner.calendar)
        from = moment
    }
    return nudges + planner.nudges(for: current, from: from, through: end)
}

let standardPlanner = NudgePlanner(calendar: tbilisi, quietHours: .standard)
