import Foundation
@testable import NudgeKit
import Testing

@Suite("Agenda")
struct AgendaTests {
    let today = Day(2026, 10, 29)

    private func task(_ title: String, first: Day, repeats: Repeat = .never, deadline: TimeOfDay = TimeOfDay(23, 59), saved: [Occurrence] = []) -> TaskSnapshot {
        TaskSnapshot(
            spec: TaskSpec(id: title, draft: TaskDraft(title: title, day: first, rule: rule(deadline: deadline), repeats: repeats), createdAt: at(Day(2026, 10, 1), 9, 0)),
            occurrences: saved
        )
    }

    private func saved(_ title: String, on day: Day, state: OccurrenceState = .open, movedTo: Day? = nil) -> Occurrence {
        var occurrence = occurrence(title, on: day, created: at(Day(2026, 10, 1), 9, 0), state: state, id: "\(title)-\(day.key)")
        occurrence.taskID = title
        if let movedTo { occurrence.day = movedTo }
        return occurrence
    }

    @Test("Today: overdue first, oldest first, then today's own, unanswered before done")
    func todayList() {
        let tasks = [
            task("Old", first: today.adding(days: -3), saved: [saved("Old", on: today.adding(days: -3))]),
            task("Older done", first: today.adding(days: -4), saved: [saved("Older done", on: today.adding(days: -4), state: .done)]),
            task("Yesterday", first: today.adding(days: -1), saved: [saved("Yesterday", on: today.adding(days: -1), state: .inProgress)]),
            task("Paid", first: today, saved: [saved("Paid", on: today, state: .done)]),
            task("Rent", first: today, deadline: TimeOfDay(18, 0), saved: [saved("Rent", on: today)]),
            task("Tomorrow", first: today.adding(days: 1)),
        ]
        let list = Agenda.today(today, in: tasks)
        #expect(list.overdue.map(\.title) == ["Old", "Yesterday"])
        #expect(list.today.map(\.title) == ["Rent", "Paid"])
    }

    @Test("A repeat's occurrence shows on its day before anything is saved for it, also when overdue")
    func upcomingOccurrences() {
        let daily = task("Vitamins", first: today.adding(days: -2), repeats: .daily, saved: [saved("Vitamins", on: today.adding(days: -2), state: .done)])
        let list = Agenda.today(today, in: [daily])
        #expect(list.overdue.map(\.day) == [today.adding(days: -1)])
        #expect(list.today.map(\.id) == ["Vitamins@\(today.key)"])
        #expect(OccurrenceRef(list.today[0].id) == .upcoming(taskID: "Vitamins", day: today))
        #expect(OccurrenceRef("3F2504E0-4F89-11D3-9A0C-0305E82C3301") == .saved("3F2504E0-4F89-11D3-9A0C-0305E82C3301"))
    }

    @Test("A moved occurrence belongs to its new day, and its series day doesn't come back as new")
    func moved() {
        let permit = task("Permit", first: today, repeats: .daily, saved: [saved("Permit", on: today, state: .moved, movedTo: today.adding(days: 1))])
        #expect(Agenda.occurrences(on: today, in: [permit]).isEmpty)
        let tomorrow = Agenda.occurrences(on: today.adding(days: 1), in: [permit])
        #expect(tomorrow.count == 2) // the moved one and tomorrow's own
        #expect(tomorrow.filter { $0.state == .moved }.count == 1)
    }

    @Test("Calendar marks: filled while anything is unanswered, hollow when all are done")
    func marks() {
        let tasks = [
            task("Done", first: today, saved: [saved("Done", on: today, state: .done)]),
            task("Open", first: today.adding(days: 1)),
            task("Both done", first: today.adding(days: 2), saved: [saved("Both done", on: today.adding(days: 2), state: .done)]),
            task("Mixed", first: today, saved: [saved("Mixed", on: today)]),
        ]
        let marks = Agenda.marks(from: today.adding(days: -1), through: today.adding(days: 3), in: tasks)
        #expect(marks == [today: .open, today.adding(days: 1): .open, today.adding(days: 2): .allDone])
    }
}

@Suite("Wording")
struct WordingTests {
    let british = Wording(calendar: tbilisi, locale: Locale(identifier: "en_GB"))
    let american = Wording(calendar: tbilisi, locale: Locale(identifier: "en_US"))
    let today = Day(2026, 10, 29)

    @Test("Times follow the locale's 12- or 24-hour clock")
    func times() {
        #expect(british.time(TimeOfDay(23, 59)) == "23:59")
        #expect(american.time(TimeOfDay(23, 59)).replacingOccurrences(of: "\u{202F}", with: " ") == "11:59 PM")
        #expect(british.time(at(today, 13, 5)) == "13:05")
        #expect(british.day(Day(2026, 10, 28)) == "28 Oct")
        #expect(american.day(Day(2026, 10, 28)) == "Oct 28")
        #expect(british.relativeDay(today.adding(days: 1), today: today) == "Tomorrow")
    }

    @Test("A wall-clock time comes out as written whatever the zone")
    func zoneFreeTimes() {
        let farAway = Wording(calendar: .gregorian(in: TimeZone(identifier: "Pacific/Kiritimati")!), locale: Locale(identifier: "en_GB"))
        #expect(farAway.time(TimeOfDay(10, 0)) == "10:00")
        #expect(farAway.day(Day(2026, 10, 28)) == "28 Oct")
    }

    @Test("Each kind of nudge says what it is")
    func bodies() {
        let bill = occurrence("Pay the credit card bill", on: Day(2026, 10, 28), rule: rule(cantMiss: true), created: at(Day(2026, 10, 27), 12, 0))
        func body(_ kind: Nudge.Kind, at moment: Date, of occurrence: Occurrence = bill) -> String {
            british.body(for: Nudge(date: moment, kind: kind), of: occurrence, shownAt: moment)
        }
        #expect(body(.headsUp, at: at(Day(2026, 10, 27), 19, 0)) == "Tomorrow is the last day: due by 23:59.")
        #expect(body(.regular, at: at(Day(2026, 10, 28), 10, 0)) == "Due today by 23:59.")
        #expect(body(.final, at: at(Day(2026, 10, 28), 21, 0)) == "Last day: due by 23:59, 2 hrs, 59 min left.")
        #expect(body(.alarm, at: at(Day(2026, 10, 28), 23, 0)) == "Less than an hour left: due by 23:59.")
        #expect(body(.deadlinePassed, at: at(Day(2026, 10, 28), 23, 59)) == "Deadline passed at 23:59.")
        #expect(body(.overdue, at: at(Day(2026, 10, 29), 10, 0)) == "Overdue since 28 Oct.")
        let started = bill.answering(.inProgress, at: at(Day(2026, 10, 28), 9, 0), calendar: tbilisi)
        #expect(body(.regular, at: at(Day(2026, 10, 28), 15, 0), of: started) == "In progress. Due today by 23:59.")
        let early = occurrence(on: Day(2026, 10, 28), rule: rule(deadline: TimeOfDay(17, 0)), created: at(Day(2026, 10, 27), 12, 0))
        #expect(body(.regular, at: at(Day(2026, 10, 28), 19, 0), of: early) == "Was due by 17:00.")
        let moved = early.answering(.notToday, at: at(Day(2026, 10, 28), 11, 0), calendar: tbilisi)
        #expect(body(.regular, at: at(Day(2026, 10, 29), 10, 0), of: moved) == "Moved from 28 Oct. Due today by 17:00.")
        #expect(british.state(of: moved) == "Moved from 28 Oct")
    }
}

@Suite("Seed")
struct SeedTests {
    @Test("A seed round-trips and an empty object isn't one")
    func formats() throws {
        let seed = Seed(tasks: [Seed.Entry(title: "Pay rent", dayOffset: -1, rule: rule(cantMiss: true), state: .inProgress, snoozedOffset: -3_540)])
        let decoded = try Seed.decode(seed.encoded())
        #expect(decoded.tasks.first?.title == "Pay rent")
        #expect(decoded.tasks.first?.rule?.cantMiss == true)
        #expect(throws: DecodingError.self) { try Seed.decode("{}") }
        #expect(throws: DecodingError.self) { try Seed.decode(#"{"format": 2, "tasks": []}"#) }
    }

    @Test("Settings need their format too")
    func settings() throws {
        #expect(throws: DecodingError.self) { try JSONDecoder().decode(NudgeSettings.self, from: Data("{}".utf8)) }
        let standard = try JSONDecoder().decode(NudgeSettings.self, from: JSONEncoder().encode(NudgeSettings.standard))
        #expect(standard == .standard)
        #expect(standard.activeQuietHours == .standard)
    }
}
