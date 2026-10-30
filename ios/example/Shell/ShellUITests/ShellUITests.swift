// THE SHELL DRIVEN as a person would: the examples' screens tapped and typed on the simulator, each step's screen
// kept (/tmp/okay-faces-ios/) — the iOS face checked end to end, the program running in its Scala Native library.
import XCTest

final class ShellUITests: XCTestCase {
  override func setUp() { continueAfterFailure = false }

  func shot(_ app: XCUIApplication, _ name: String) {
    let png = XCUIScreen.main.screenshot().pngRepresentation
    try? FileManager.default.createDirectory(atPath: "/tmp/okay-faces-ios", withIntermediateDirectories: true)
    try? png.write(to: URL(fileURLWithPath: "/tmp/okay-faces-ios/\(name).png"))
    let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
  }

  func testTheList() {
    let app = XCUIApplication()
    app.launchArguments = ["-app", "okay.faces.ios.example.TodoApp"]
    app.launch()
    XCTAssert(app.staticTexts["Nothing to do."].waitForExistence(timeout: 10))
    shot(app, "todo-1-empty")

    let title = app.textFields["field-title"]
    title.tap(); title.typeText("Milk")
    app.buttons["act-add"].tap()
    XCTAssert(app.staticTexts["added: Milk"].waitForExistence(timeout: 5))
    XCTAssertEqual(title.value as? String, "Title", "the form drawn afresh after a step")

    title.tap(); title.typeText("Bread")
    app.buttons["add-due"].tap()
    app.buttons["act-add"].tap()
    XCTAssert(app.buttons["ref-2"].waitForExistence(timeout: 5))
    shot(app, "todo-2-two-items")

    app.buttons["ref-1"].tap()
    XCTAssert(app.staticTexts["1 of 2 done"].waitForExistence(timeout: 5))
    shot(app, "todo-3-one-done")

    app.buttons["act-remove"].tap()
    XCTAssert(app.staticTexts["Remove which?"].waitForExistence(timeout: 5))
    shot(app, "todo-4-remove-which")
  }

  func testNoughts() {
    let app = XCUIApplication()
    app.launchArguments = ["-app", "okay.faces.ios.example.NoughtsApp"]
    app.launch()
    XCTAssert(app.buttons["ref-5"].waitForExistence(timeout: 10))
    shot(app, "noughts-1-board")
    app.buttons["ref-5"].tap()
    XCTAssert(!app.buttons["ref-5"].waitForExistence(timeout: 2))
    shot(app, "noughts-2-moved")
  }

  /// the device: a note kept between runs, GitHub's page fetched, a reminder notified
  func testNotesOnTheDevice() {
    let app = XCUIApplication()
    app.launchArguments = ["-app", "okay.faces.ios.example.NotesApp"]
    app.launch()
    if app.buttons["act-clear"].waitForExistence(timeout: 5) { app.buttons["act-clear"].tap() }
    XCTAssert(app.staticTexts["Nothing kept yet."].waitForExistence(timeout: 10))
    let text = app.textFields["field-text"]
    text.tap(); text.typeText("Buy milk")
    app.buttons["act-add"].tap()
    XCTAssert(app.staticTexts["1 kept on this phone"].waitForExistence(timeout: 5))

    app.terminate()
    app.launch()
    XCTAssert(app.staticTexts["Buy milk"].waitForExistence(timeout: 10), "kept between runs")
    shot(app, "notes-1-kept")

    app.buttons["act-github"].tap()
    XCTAssert(app.staticTexts["Stars"].waitForExistence(timeout: 30), "GitHub's answer shown")
    shot(app, "notes-2-github")

    app.buttons["act-remind"].tap()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let allow = springboard.buttons["Allow"]
    if allow.waitForExistence(timeout: 5) { allow.tap() }
    XCTAssert(app.staticTexts["A reminder in 5 seconds."].waitForExistence(timeout: 10))
    let banner = springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Buy milk'"))
      .firstMatch
    XCTAssert(banner.waitForExistence(timeout: 15), "the reminder notified")
    shot(app, "notes-3-reminded")
  }
}
