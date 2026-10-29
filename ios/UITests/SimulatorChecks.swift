import NudgeKit
import XCTest

/// Checks made by hand in the simulator, through SpringBoard: placing the widget and tapping its
/// Done. Too dependent on SpringBoard's layout to run with the suite, so they run only when
/// ARDAIVITSKO_SIMULATOR_CHECKS is set in the test's environment, and save screenshots to
/// ARDAIVITSKO_SHOTS (a folder on the Mac running the simulator).
final class SimulatorChecks: ArDaivitskoUITestCase {
    private var shots: URL? {
        ProcessInfo.processInfo.environment["ARDAIVITSKO_SHOTS"].map { URL(fileURLWithPath: $0) }
    }

    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["ARDAIVITSKO_SIMULATOR_CHECKS"] != nil, "Run by hand: needs SpringBoard")
    }

    @MainActor
    private func save(_ name: String) {
        guard let shots else { return }
        try? FileManager.default.createDirectory(at: shots, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: shots.appending(path: "\(name).png"))
    }

    @MainActor
    func testTheWidgetsDoneAnswersWithoutOpeningTheApp() throws {
        var bill = NudgeRule.standard
        bill.cantMiss = true
        var settings = NudgeSettings.standard
        settings.quietHoursOn = false
        let app = try launch(with: [
            Seed.Entry(title: "Pay rent"),
            Seed.Entry(title: "Pay the credit card bill", rule: bill),
            Seed.Entry(title: "Renew the parking permit", dayOffset: -2, createdOffset: -4 * 86_400),
        ], settings: settings)
        XCTAssertTrue(row("Pay rent", in: app).waitForExistence(timeout: 30))

        XCUIDevice.shared.press(.home)
        placeWidget(named: "Ar Daivitsko")
        let done = springboard.buttons["Mark Pay rent done"]
        XCTAssertTrue(done.waitForExistence(timeout: 30), "The widget's Done button for Pay rent never appeared")
        save("widget-before")

        done.tap()
        XCTAssertTrue(done.waitForNonExistence(timeout: 30), "Pay rent stayed on the widget")
        XCTAssertTrue(springboard.buttons["Mark Pay the credit card bill done"].exists)
        save("widget-after")

        app.activate()
        waitForValue(of: row("Pay rent", in: app), containing: "Done")
        tapWhenHittable(app.buttons["Settings"])
        let pending = app.staticTexts["pending.Pay rent"]
        scroll(to: pending, in: app)
        XCTAssertEqual(pending.label, "Pay rent: 0 pending")
    }

    /// The Can't-miss alarm through AlarmKit (iOS 26): the real one is scheduled for its slot, and
    /// a test alarm built the same way sounds 45 seconds after launch, full screen over the Lock
    /// Screen, with Done and slide to stop.
    ///
    /// The alarm's own Done runs CompleteTaskIntent from AlarmKit's daemon, which in the simulator
    /// fails for an ad-hoc signed app: linkd can't identify it ("Unable to get teamId"), so the app
    /// finds no metadata for the intent. A team-signed build on a phone should run it; that's
    /// tried here without failing the check.
    @MainActor
    func testTheCantMissAlarmSoundsOverTheLockScreen() throws {
        var bill = NudgeRule.standard
        bill.cantMiss = true
        var settings = NudgeSettings.standard
        settings.quietHoursOn = false
        XCUIDevice.shared.press(.home) // wakes and unlocks a simulator left locked
        let app = XCUIApplication()
        app.launchEnvironment[Seed.environmentKey] = try Seed(tasks: [Seed.Entry(title: "Pay the credit card bill", rule: bill)], settings: settings).encoded()
        app.launchEnvironment["ARDAIVITSKO_TEST_ALARM_IN"] = "45"
        app.launch()
        allowPrompts(timeout: 5)

        // The planned alarm, at 23:00 for a 23:59 deadline, is with AlarmKit.
        tapWhenHittable(app.buttons["Settings"])
        let planned = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH '23:00' AND label CONTAINS 'Pay the credit card bill'")).firstMatch
        scroll(to: planned, in: app)
        save("alarm-scheduled")
        tapWhenHittable(app.buttons["Today"])

        XCUIDevice.shared.perform(NSSelectorFromString("pressLockButton"))
        let alarmDone = springboard.buttons["Done"]
        XCTAssertTrue(alarmDone.waitForExistence(timeout: 120), "The alarm never sounded")
        sleep(2)
        save("alarm-sounding")

        let task = row("Pay the credit card bill", in: app)
        let options = XCTExpectedFailure.Options()
        options.isStrict = false
        XCTExpectFailure("The alarm's Done can't run its intent for an ad-hoc signed app in the simulator", options: options) {
            alarmDone.tap()
            sleep(5)
            XCUIDevice.shared.press(.home)
            app.activate()
            XCTAssertEqual(XCTWaiter().wait(for: [expectation(for: NSPredicate(format: "value CONTAINS 'Done'"), evaluatedWith: task)], timeout: 10), .completed)
        }
        // Leave nothing sounding: answering in the app stops it.
        XCUIDevice.shared.press(.home)
        app.activate()
        if !((task.value as? String) ?? "").contains("Done") {
            tapWhenHittable(app.buttons["Mark Pay the credit card bill done"])
        }
    }

    /// While the alarm sounds over the open app, Done in the app silences it.
    @MainActor
    func testAnsweringInTheAppStopsASoundingAlarm() throws {
        var bill = NudgeRule.standard
        bill.cantMiss = true
        var settings = NudgeSettings.standard
        settings.quietHoursOn = false
        XCUIDevice.shared.press(.home)
        let app = XCUIApplication()
        app.launchEnvironment[Seed.environmentKey] = try Seed(tasks: [Seed.Entry(title: "Pay the credit card bill", rule: bill)], settings: settings).encoded()
        app.launchEnvironment["ARDAIVITSKO_TEST_ALARM_IN"] = "30"
        app.launch()
        allowPrompts(timeout: 5)
        let alarm = springboard.buttons["Done"]
        XCTAssertTrue(alarm.waitForExistence(timeout: 120), "The alarm never sounded")
        save("alarm-over-app")
        tapWhenHittable(app.buttons["Mark Pay the credit card bill done"])
        waitForValue(of: row("Pay the credit card bill", in: app), containing: "Done")
        XCTAssertTrue(alarm.waitForNonExistence(timeout: 30), "The alarm kept sounding after the task was answered")
        save("alarm-stopped")
    }

    /// Long-press the Home Screen, Edit, Add Widget, search, pick the app, pick the medium size, add.
    @MainActor
    private func placeWidget(named name: String) {
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75)).press(forDuration: 1.5)
        let edit = springboard.buttons["Edit"]
        if edit.waitForExistence(timeout: 10) {
            edit.tap()
            let addWidget = springboard.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'Add Widget'")).firstMatch
            XCTAssertTrue(addWidget.waitForExistence(timeout: 10))
            addWidget.tap()
        }
        let search = springboard.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 15), "No widget search field")
        search.tap()
        search.typeText(name)
        let cell = springboard.cells.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
        XCTAssertTrue(cell.waitForExistence(timeout: 15), "\(name) isn't in the widget gallery")
        cell.tap()
        // The gallery opens on the small size; the medium one is a swipe away.
        sleep(2)
        springboard.swipeLeft()
        sleep(1)
        let add = springboard.buttons.matching(NSPredicate(format: "label ENDSWITH 'Add Widget'")).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 15), "No Add Widget button")
        add.tap()
        let finish = springboard.buttons["Done"]
        if finish.waitForExistence(timeout: 10) {
            finish.tap()
        }
    }
}
