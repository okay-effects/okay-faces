// THE APPLICATION'S DIALOGUE, through its library's C functions (specs/ios.md): open it by its class name, send a
// choice, go back; a screen each time. One a process, as the library keeps one.
import Foundation
import COkayFaces
#if canImport(UIKit)
import UIKit
#endif

@MainActor
public final class Dialogue: ObservableObject {
  @Published private(set) var screen = Screen.empty
  @Published private(set) var look = LookSpec()
  /// a new step each time a choice is taken: the forms drawn afresh; a rejected one keeps what was typed
  @Published private(set) var turn = 0
  private static var started = false
  fileprivate static weak var current: Dialogue?

  /// the application, by the class name of its `IosApp`
  public init(app: String) {
    if !Dialogue.started { _ = ScalaNativeInit(); Dialogue.started = true }
    Dialogue.current = self
    screen = Screen(Dialogue.read(okay_faces_open(app)))
    look = LookSpec(Dialogue.read(okay_faces_look()))
    // an event moved the dialogue on by itself (a timer, a message): the screen asked again, on the main thread
    okay_faces_on_moved {
      DispatchQueue.main.async { Dialogue.current?.refresh() }
    }
  }

  /// the action taken with the fields typed, by their dotted names
  public func act(_ action: String, _ form: [String: [String]] = [:]) {
    let choice: JSON = ["action": action, "form": form]
    guard let data = try? JSONSerialization.data(withJSONObject: choice) else { return }
    let text = String(decoding: data, as: UTF8.self)
    screen = Screen(Dialogue.read(text.withCString { okay_faces_act($0) }))
    if screen.problems.isEmpty {
      turn += 1
      #if canImport(UIKit)
      UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
      #endif
    }
  }

  public func back() { screen = Screen(Dialogue.read(okay_faces_back())); turn += 1 }
  public func refresh() { screen = Screen(Dialogue.read(okay_faces_screen())) }

  /// a string of the library's as JSON, and the string freed
  private static func read(_ p: UnsafeMutablePointer<CChar>?) -> JSON {
    guard let p else { return ["error": "no answer"] }
    defer { okay_faces_free(p) }
    let data = Data(String(cString: p).utf8)
    return (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? ["error": "not JSON"]
  }
}
