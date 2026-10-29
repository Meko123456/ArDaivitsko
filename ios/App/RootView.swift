import NudgeKit
import Nudger
import SwiftUI

/// Today, the calendar and the settings, with what a notification asks of the open app: the task
/// it opened, and "This is the last day. Move it anyway?".
struct RootView: View {
    @Environment(Nudger.self) private var nudger
    @Environment(\.scenePhase) private var scenePhase
    @State private var now = Date.now

    var body: some View {
        @Bindable var nudger = nudger
        TabView {
            NavigationStack {
                TodayView(now: now)
            }
            .tabItem { Label("Today", systemImage: "checklist") }

            NavigationStack {
                CalendarView(now: now)
            }
            .tabItem { Label("Calendar", systemImage: "calendar") }

            NavigationStack {
                SettingsView()
            }
            .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .tint(.ardaivitsko)
        .sheet(item: Binding(
            get: { nudger.opened.map(OpenedOccurrence.init) },
            set: { nudger.opened = $0?.id }
        )) { opened in
            NavigationStack {
                TaskDetailView(ref: opened.id, now: now)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Close") { nudger.opened = nil }
                        }
                    }
            }
        }
        .alert(
            "This is the last day. Move it anyway?",
            isPresented: Binding(get: { nudger.lastDayQuestion != nil }, set: { if !$0 { nudger.lastDayQuestion = nil } }),
            presenting: nudger.lastDayQuestion
        ) { id in
            Button("Move to Tomorrow") {
                Task { await nudger.answer(.notToday, ref: id, confirmed: true) }
            }
            Button("Keep It Today", role: .cancel) {}
        } message: { id in
            if let occurrence = Agenda.occurrence(id, in: nudger.store.snapshots()) {
                Text("\(occurrence.title) can't be missed, and its deadline is today at \(nudger.wording.time(occurrence.rule.deadline)).")
            }
        }
        .modifier(MinuteClock(now: $now))
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { now = .now }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            // Midnight, a time zone change, or the clock set by hand: everything moves.
            now = .now
            Task { await nudger.refresh() }
        }
    }
}

private struct OpenedOccurrence: Identifiable {
    let id: String
}

/// Offers Undo for a few seconds after Not today in the app, above the screen's bottom edge.
struct UndoMoveOffer: ViewModifier {
    @Environment(Nudger.self) private var nudger

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom) {
                if let move = nudger.lastMove {
                    UndoMoveBar(move: move)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.default, value: nudger.lastMove)
    }
}

/// "Pay rent moved to tomorrow", with Undo.
struct UndoMoveBar: View {
    @Environment(Nudger.self) private var nudger
    let move: Nudger.Move

    var body: some View {
        HStack(spacing: 12) {
            Text("\(move.title) moved to \(nudger.wording.relativeDay(move.day, today: nudger.today).lowercased())")
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Undo") {
                Task { await nudger.undoMove(occurrenceID: move.occurrenceID, movedAt: move.movedAt) }
            }
            .font(.subheadline.weight(.semibold))
            .accessibilityLabel("Undo moving \(move.title)")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .shadow(radius: 6, y: 2)
        .accessibilityElement(children: .contain)
        .task(id: move.movedAt) {
            do {
                try await Task.sleep(for: .seconds(8))
            } catch {
                return
            }
            nudger.dismissUndo(move)
        }
    }
}

/// Moves `now` on at each minute, and when the clock or time zone changes, so "next nudge" and
/// which day is today stay right. A sleep to the next minute, not a TimelineView: that draws
/// its entries ahead of their time.
struct MinuteClock: ViewModifier {
    @Binding var now: Date

    func body(content: Content) -> some View {
        content.task(id: now) {
            let next = (now.timeIntervalSinceReferenceDate / 60).rounded(.down) * 60 + 60
            do {
                try await Task.sleep(for: .seconds(max(1, next - Date.now.timeIntervalSinceReferenceDate)))
            } catch {
                return
            }
            now = .now
        }
    }
}
