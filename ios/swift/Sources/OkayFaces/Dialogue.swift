// THE APPLICATION'S DIALOGUE, through its library's C functions (specs/ios.md): open it by its class name, send a
// choice, go back; a screen each time. Each call made on the library's own thread (`ScalaThread`) — a step may wait
// for the network — and its screen published on the main one. One a process, as the library keeps one.
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
  /// the library at work: a step being taken, a page being fetched
  @Published private(set) var busy = true
  fileprivate static weak var current: Dialogue?

  /// the application, by the class name of its `IosApp`
  public init(app: String) {
    Dialogue.current = self
    call(fresh: true) {
      // an event moved the dialogue on by itself (a timer, a message): the screen asked again
      okay_faces_on_moved { DispatchQueue.main.async { Dialogue.current?.refresh() } }
      let screen = Dialogue.read(okay_faces_open(app))
      let look = Dialogue.read(okay_faces_look())
      return (screen, look)
    }
  }

  /// the action taken with the fields typed, by their dotted names
  public func act(_ action: String, _ form: [String: [String]] = [:]) {
    let choice: JSON = ["action": action, "form": form]
    guard let data = try? JSONSerialization.data(withJSONObject: choice) else { return }
    let text = String(decoding: data, as: UTF8.self)
    #if canImport(UIKit)
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    #endif
    call(fresh: false) { (Dialogue.read(text.withCString { okay_faces_act($0) }), nil) }
  }

  public func back() { call(fresh: true) { (Dialogue.read(okay_faces_back()), nil) } }
  public func refresh() { call(fresh: false) { (Dialogue.read(okay_faces_screen()), nil) } }

  /// a call on the library's thread; its screen (and look) here after. `fresh`: the forms drawn afresh whatever came
  private func call(fresh: Bool, _ work: @escaping () -> (JSON, JSON?)) {
    busy = true
    ScalaThread.shared.run {
      let (s, l) = work()
      DispatchQueue.main.async {
        let screen = Screen(s)
        if let l { self.look = LookSpec(l) }
        if fresh || screen.problems.isEmpty { self.turn += 1 }
        self.screen = screen
        self.busy = false
      }
    }
  }

  /// a string of the library's as JSON, and the string freed
  nonisolated private static func read(_ p: UnsafeMutablePointer<CChar>?) -> JSON {
    guard let p else { return ["error": "no answer"] }
    defer { okay_faces_free(p) }
    let data = Data(String(cString: p).utf8)
    return (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? ["error": "not JSON"]
  }
}
