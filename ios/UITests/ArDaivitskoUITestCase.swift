import NudgeKit
import XCTest

/// What every Ar Daivitsko UI test needs: a launch with the tasks the test chooses, and the
/// permission prompts answered.
class ArDaivitskoUITestCase: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
        // Seeded tasks make the app ask to send notifications (and, for a Can't-miss task, to sound
        // alarms). Whichever test meets a prompt allows it, the moment it next touches the app.
        addUIInterruptionMonitor(withDescription: "Permissions") { alert in
            let allow = alert.buttons["Allow"]
            guard allow.exists else { return false }
            allow.tap()
            return true
        }
    }

    /// A first launch before each class takes over a copy of the app the system may have started
    /// in the background after the reinstall, so every test's launch gets its own seed.
    override class func setUp() {
        super.setUp()
        MainActor.assumeIsolated {
            let app = XCUIApplication()
            app.launch()
            app.terminate()
        }
    }

    /// Launches with `tasks` (relative to now) and nothing else, quiet hours as in `settings`.
    @MainActor
    func launch(with tasks: [Seed.Entry] = [], settings: NudgeSettings? = nil) throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment[Seed.environmentKey] = try Seed(tasks: tasks, settings: settings).encoded()
        app.launch()
        allowPrompts()
        return app
    }

    /// A prompt an earlier launch raised outlives that app, and hides this one's screen from
    /// queries until it's answered.
    @MainActor
    func allowPrompts(timeout: TimeInterval = 3) {
        let allow = springboard.alerts.buttons["Allow"]
        while allow.waitForExistence(timeout: timeout) {
            allow.tap()
        }
    }

    /// The Home Screen, notifications and system alerts.
    @MainActor
    var springboard: XCUIApplication {
        XCUIApplication(bundleIdentifier: "com.apple.springboard")
    }

    /// Taps once the element can take it: a tap made as soon as it exists can go nowhere.
    @MainActor
    func tapWhenHittable(_ element: XCUIElement, timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(element) never appeared", file: file, line: line)
        let hittable = expectation(for: NSPredicate(format: "isHittable == true"), evaluatedWith: element)
        wait(for: [hittable], timeout: timeout)
        element.tap()
    }

    /// Types once the field has the keyboard's focus.
    @MainActor
    func type(_ text: String, into field: XCUIElement) {
        tapWhenHittable(field)
        let focused = expectation(for: NSPredicate(format: "hasKeyboardFocus == true"), evaluatedWith: field)
        if XCTWaiter().wait(for: [focused], timeout: 10) != .completed {
            field.tap()
        }
        field.typeText(text)
    }

    /// A text field by its identifier, whether SwiftUI made it a field or (growing vertically) a view.
    @MainActor
    func field(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ AND (elementType == %d OR elementType == %d)", identifier, XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue)).firstMatch
    }

    /// Turns a Form toggle on or off by tapping its switch, not its label.
    @MainActor
    func toggle(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 30))
        let before = element.value as? String
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        let changed = NSPredicate(format: "value != %@", before ?? "")
        XCTAssertEqual(XCTWaiter().wait(for: [expectation(for: changed, evaluatedWith: element)], timeout: 10), .completed, "\(element.label) didn't change")
    }

    /// A Today or calendar row, by its task's title; its value says where it stands.
    @MainActor
    func row(_ title: String, in app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@ AND value != nil", title)).firstMatch
    }

    /// Scrolls until `element` exists: a List builds only the rows on screen.
    @MainActor
    func scroll(to element: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        var swipes = 0
        while !element.waitForExistence(timeout: 2), swipes < 8 {
            app.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(element.exists, "\(element) not found after \(swipes) swipes", file: file, line: line)
    }

    /// Waits until an element's accessibility value contains `text`.
    @MainActor
    func waitForValue(of element: XCUIElement, containing text: String, timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: "value CONTAINS %@", text)
        let found = XCTWaiter().wait(for: [expectation(for: predicate, evaluatedWith: element)], timeout: timeout)
        XCTAssertEqual(found, .completed, "\(element.label) never said \(text); it says \(element.value ?? "nothing")", file: file, line: line)
    }
}
