import AppIntents
import NudgeKit
import SwiftUI
import TaskStore
import WidgetKit

struct TodayEntry: TimelineEntry {
    let date: Date
    let day: Day
    /// Overdue first, oldest first, then today's unanswered tasks.
    let tasks: [Occurrence]
}

/// Reads the tasks the app saved in the app group. The app reloads the widget after every change;
/// in between, the timeline turns over at midnight, when yesterday's unanswered tasks become overdue.
struct TodayTimeline: TimelineProvider {
    func placeholder(in context: Context) -> TodayEntry {
        TodayEntry(date: .now, day: Self.day(of: .now), tasks: Self.samples)
    }

    func getSnapshot(in context: Context, completion: @escaping (TodayEntry) -> Void) {
        if context.isPreview {
            completion(placeholder(in: context))
        } else {
            completion(entries(from: .now).first!)
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TodayEntry>) -> Void) {
        completion(Timeline(entries: entries(from: .now), policy: .atEnd))
    }

    private func entries(from now: Date) -> [TodayEntry] {
        let calendar = Calendar.gregorian(in: .autoupdatingCurrent)
        let snapshots = (try? StoreLocation.shared()).map(StoreLocation.snapshots(in:)) ?? []
        let today = Day(now, in: calendar)
        let midnight = today.adding(days: 1).start(in: calendar)
        return [
            TodayEntry(date: now, day: today, tasks: Self.unanswered(on: today, in: snapshots)),
            TodayEntry(date: midnight, day: today.adding(days: 1), tasks: Self.unanswered(on: today.adding(days: 1), in: snapshots)),
        ]
    }

    /// Overdue first, then today's; a repeating task's quiet earlier days come last, so the ones
    /// that nudge stay in sight on a small widget.
    static func unanswered(on day: Day, in snapshots: [TaskSnapshot]) -> [Occurrence] {
        let list = Agenda.today(day, in: snapshots)
        let all = list.overdue + list.today.filter { !$0.isAnswered }
        return all.filter { !$0.isQuiet(today: day) } + all.filter { $0.isQuiet(today: day) }
    }

    static func day(of date: Date) -> Day {
        Day(date, in: .gregorian(in: .autoupdatingCurrent))
    }

    /// Made-up tasks for the widget gallery, never the person's own.
    static var samples: [Occurrence] {
        let today = day(of: .now)
        var bill = NudgeRule.standard
        bill.cantMiss = true
        return [
            Occurrence(id: "sample-1", taskID: "s1", title: "Renew the parking permit", seriesDay: today.adding(days: -1), rule: .standard, createdAt: .distantPast),
            Occurrence(id: "sample-2", taskID: "s2", title: "Pay the credit card bill", seriesDay: today, rule: bill, createdAt: .distantPast),
            Occurrence(id: "sample-3", taskID: "s3", title: "Call the bank", seriesDay: today, rule: .standard, state: .inProgress, createdAt: .distantPast),
        ]
    }
}

/// Today's unanswered tasks, overdue first, each with a Done button that answers without opening
/// the app.
struct TodayWidget: Widget {
    static let kind = "Today"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: TodayTimeline()) { entry in
            TodayWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
                .widgetURL(URL(string: "ardaivitsko://today"))
        }
        .configurationDisplayName("Today")
        .description("Today's tasks still to answer, overdue first, each with a Done button.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodayEntry

    private var capacity: Int {
        switch family {
        case .systemSmall: 3
        case .systemMedium: 3
        default: 7
        }
    }

    var body: some View {
        let wording = Wording(calendar: .gregorian(in: .autoupdatingCurrent), locale: .autoupdatingCurrent)
        let shown = entry.tasks.prefix(capacity)
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(family == .systemSmall ? "Today" : wording.longDay(entry.day))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.ardaivitsko)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if !entry.tasks.isEmpty {
                    Text("\(entry.tasks.count)")
                        .font(.subheadline.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("\(entry.tasks.count) to answer")
                }
            }
            if shown.isEmpty {
                Spacer(minLength: 0)
                Label("Nothing to answer", systemImage: "checkmark.circle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else {
                ForEach(shown) { occurrence in
                    TaskLine(occurrence: occurrence, day: entry.day, wording: wording, compact: family == .systemSmall)
                }
                if entry.tasks.count > shown.count {
                    Text("\(entry.tasks.count - shown.count) more")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        }
    }
}

private struct TaskLine: View {
    let occurrence: Occurrence
    let day: Day
    let wording: Wording
    let compact: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Button(intent: CompleteTaskIntent(occurrenceID: occurrence.id)) {
                Image(systemName: "circle")
                    .font(compact ? .body : .title3)
                    .foregroundStyle(.ardaivitsko)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Mark \(occurrence.title) done")
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    Text(occurrence.title)
                        .font(compact ? .caption.weight(.medium) : .subheadline.weight(.medium))
                        .lineLimit(1)
                    if occurrence.rule.cantMiss {
                        Image(systemName: "exclamationmark.circle.fill")
                            .font(.caption2)
                            .foregroundStyle(.ardaivitsko)
                            .accessibilityLabel("Can't miss")
                    }
                }
                if !compact || occurrence.isOverdue(today: day) {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(occurrence.isOverdue(today: day) ? .overdue : .secondary)
                        .lineLimit(1)
                }
            }
        }
    }

    private var detail: String {
        if occurrence.isOverdue(today: day) {
            return wording.overdueSince(occurrence)
        }
        let due = "Due by \(wording.time(occurrence.rule.deadline))"
        return occurrence.state == .inProgress ? "In progress · \(due)" : due
    }
}

#Preview("Today", as: .systemMedium) {
    TodayWidget()
} timeline: {
    TodayEntry(date: .now, day: TodayTimeline.day(of: .now), tasks: TodayTimeline.samples)
    TodayEntry(date: .now, day: TodayTimeline.day(of: .now), tasks: [])
}
