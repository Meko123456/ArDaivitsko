import NudgeKit
import XCTest

/// Adding a task, and answering one in the app: Done, In progress, Not today and its Undo, and
/// the question a Can't-miss task asks on its last day.
final class TaskUITests: ArDaivitskoUITestCase {
    private var noQuietHours: NudgeSettings {
        var settings = NudgeSettings.standard
        settings.quietHoursOn = false
        return settings
    }

    @MainActor
    func testAddingATaskPutsItOnToday() throws {
        let app = try launch()
        XCTAssertTrue(app.staticTexts["Nothing to answer today"].waitForExistence(timeout: 30))
        tapWhenHittable(app.buttons["New Task"])
        type("Buy medicine", into: field("Title", in: app))
        tapWhenHittable(app.buttons["Save"])
        // The first task asks to send notifications.
        allowPrompts()
        let medicine = row("Buy medicine", in: app)
        XCTAssertTrue(medicine.waitForExistence(timeout: 30))
        waitForValue(of: medicine, containing: "Due by")
        XCTAssertFalse(app.staticTexts["Nothing to answer today"].exists)
    }

    @MainActor
    func testAddingACantMissTask() throws {
        let app = try launch()
        tapWhenHittable(app.buttons["New Task"])
        type("Pay the credit card bill", into: field("Title", in: app))
        toggle(app.switches["Can't miss"])
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'A heads-up at'")).firstMatch.waitForExistence(timeout: 10))
        tapWhenHittable(app.buttons["Save"])
        allowPrompts()
        // Today was picked; the bill is on it, can't be missed, and nudges.
        let bill = row("Pay the credit card bill", in: app)
        XCTAssertTrue(bill.waitForExistence(timeout: 30))
        waitForValue(of: bill, containing: "Can't miss")
    }

    @MainActor
    func testDoneFromTheRowAndBackAgain() throws {
        let app = try launch(with: [Seed.Entry(title: "Pay rent")], settings: noQuietHours)
        tapWhenHittable(app.buttons["Mark Pay rent done"])
        let rent = row("Pay rent", in: app)
        waitForValue(of: rent, containing: "Done")
        XCTAssertFalse((rent.value as? String ?? "").contains("Next nudge"))
        tapWhenHittable(app.buttons["Mark Pay rent not done"])
        waitForValue(of: rent, containing: "Next nudge")
    }

    @MainActor
    func testDoneLeavesNoNudgesPendingForThatTask() throws {
        let app = try launch(with: [Seed.Entry(title: "Pay rent"), Seed.Entry(title: "Call the bank")], settings: noQuietHours)
        tapWhenHittable(app.buttons["Settings"])
        let rentPending = app.staticTexts["pending.Pay rent"]
        scroll(to: rentPending, in: app)
        XCTAssertNotEqual(rentPending.label, "Pay rent: 0 pending")
        tapWhenHittable(app.buttons["Today"])
        tapWhenHittable(app.buttons["Mark Pay rent done"])
        waitForValue(of: row("Pay rent", in: app), containing: "Done")
        tapWhenHittable(app.buttons["Settings"])
        scroll(to: app.buttons["Reload"], in: app)
        tapWhenHittable(app.buttons["Reload"])
        let none = NSPredicate(format: "label == 'Pay rent: 0 pending'")
        XCTAssertEqual(XCTWaiter().wait(for: [expectation(for: none, evaluatedWith: rentPending)], timeout: 30), .completed, rentPending.label)
        XCTAssertNotEqual(app.staticTexts["pending.Call the bank"].label, "Call the bank: 0 pending")
    }

    @MainActor
    func testInProgressFromTheTask() throws {
        let app = try launch(with: [Seed.Entry(title: "Call the bank")], settings: noQuietHours)
        tapWhenHittable(row("Call the bank", in: app))
        tapWhenHittable(app.buttons["In Progress"])
        XCTAssertTrue(app.buttons["Still in Progress"].waitForExistence(timeout: 30))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitForValue(of: row("Call the bank", in: app), containing: "In progress")
    }

    @MainActor
    func testNotTodayMovesItToTomorrowAndUndoBringsItBack() throws {
        let app = try launch(with: [Seed.Entry(title: "Renew the parking permit")], settings: noQuietHours)
        let permit = row("Renew the parking permit", in: app)
        tapWhenHittable(permit)
        tapWhenHittable(app.buttons["Not Today"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Nothing to answer today"].waitForExistence(timeout: 30))
        // Undo, offered for a few seconds, brings it back.
        tapWhenHittable(app.buttons["Undo moving Renew the parking permit"])
        waitForValue(of: permit, containing: "Due by")

        // Moved again, it's on tomorrow in the calendar.
        tapWhenHittable(permit)
        tapWhenHittable(app.buttons["Not Today"])
        app.navigationBars.buttons.element(boundBy: 0).tap()
        tapWhenHittable(app.buttons["Calendar"])
        let marked = app.buttons.matching(NSPredicate(format: "value CONTAINS 'Tasks to answer'")).firstMatch
        if !marked.waitForExistence(timeout: 10) {
            // Today is the month's last day: tomorrow is next month's first.
            tapWhenHittable(app.buttons["Next month"])
        }
        tapWhenHittable(marked)
        let moved = row("Renew the parking permit", in: app)
        XCTAssertTrue(moved.waitForExistence(timeout: 30))
        waitForValue(of: moved, containing: "Moved from")
    }

    @MainActor
    func testNotTodayOnACantMissTasksLastDayAsksFirst() throws {
        var rule = NudgeRule.standard
        rule.cantMiss = true
        let app = try launch(with: [Seed.Entry(title: "Pay the credit card bill", rule: rule)], settings: noQuietHours)
        let bill = row("Pay the credit card bill", in: app)
        tapWhenHittable(bill)
        tapWhenHittable(app.buttons["Not Today"])
        let question = app.alerts["This is the last day. Move it anyway?"]
        XCTAssertTrue(question.waitForExistence(timeout: 30))
        question.buttons["Keep It Today"].tap()
        XCTAssertTrue(question.waitForNonExistence(timeout: 30))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(bill.waitForExistence(timeout: 30))

        tapWhenHittable(bill)
        tapWhenHittable(app.buttons["Not Today"])
        XCTAssertTrue(question.waitForExistence(timeout: 30))
        question.buttons["Move to Tomorrow"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Nothing to answer today"].waitForExistence(timeout: 30))
    }

    @MainActor
    func testOverdueComesFirst() throws {
        let app = try launch(with: [
            Seed.Entry(title: "Today's task", dayOffset: 0),
            Seed.Entry(title: "Old task", dayOffset: -2, createdOffset: -4 * 86_400),
        ], settings: noQuietHours)
        let old = row("Old task", in: app)
        let todays = row("Today's task", in: app)
        XCTAssertTrue(old.waitForExistence(timeout: 30))
        XCTAssertTrue(todays.waitForExistence(timeout: 30))
        waitForValue(of: old, containing: "Overdue since")
        XCTAssertLessThan(old.frame.minY, todays.frame.minY)
        XCTAssertTrue(app.staticTexts["Overdue"].exists)
    }
}
