// THE APPLICATION'S DIALOGUE, through its library's C functions (specs/mobile.md): open it by its class name, send a
// choice, go back; a screen each time. Each call made on the library's own thread (`ScalaThread`) — a step may wait
// for the network — and its screen published on the main one. One a process, as the library keeps one.
import Foundation
import COkayFaces
#if canImport(UIKit)
import UIKit
#endif

@MainActor
public final class Dialogue: ObservableObject {
  /// the steps shown, the last the current one: the ones before it those one may go back to
  @Published private(set) var stack: [Screen] = [Screen.empty]
  /// the look as the application gives it, both palettes: the face takes the one the phone is set to
  @Published private(set) var lookJSON: JSON = [:]
  var screen: Screen { stack.last ?? Screen.empty }
  /// a new step each time a choice is taken: the forms drawn afresh; a rejected one keeps what was typed
  @Published private(set) var turn = 0
  /// the library at work: a step being taken, a page being fetched
  @Published private(set) var busy = true
  fileprivate static weak var current: Dialogue?

  /// the application, by the class name of its `MobileApp`
  public init(app: String) {
    Dialogue.current = self
    call(.replace) {
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
    UIImpactFeedbackGenerator(style: .light).impactOccurred()
    #endif
    call(.forward) { (Dialogue.read(text.withCString { okay_faces_act($0) }), nil) }
  }

  /// the step before: popped at once (the edge of the screen already took it away), then asked of the library
  public func back() {
    if stack.count > 1 { stack.removeLast() }
    call(.replace) { (Dialogue.read(okay_faces_back()), nil) }
  }
  public func refresh() { call(.refresh) { (Dialogue.read(okay_faces_screen()), nil) } }

  /// where a screen goes: deeper (a step one may come back to), in the place of the last, or as it was refreshed
  private enum Move { case forward, replace, refresh }

  /// a call on the library's thread; its screen (and look) here after
  private func call(_ move: Move, _ work: @escaping () -> (JSON, JSON?)) {
    busy = true
    ScalaThread.shared.run {
      let (s, l) = work()
      DispatchQueue.main.async {
        let screen = Screen(s)
        if let l { self.lookJSON = l }
        switch move {
        case .forward where !screen.problems.isEmpty:
          self.stack[self.stack.count - 1] = screen          // rejected: the same step, what was typed kept
        case .forward:
          self.turn += 1
          // a step one may go back from goes deeper — unless it is a page already there (the same title): then that
          // page, as it is now, and what was above it gone; a step with nothing behind starts the stack again
          if !screen.back { self.stack = [screen] }
          else if let t = screen.title, let i = self.stack.lastIndex(where: { $0.title == t }) {
            self.stack = Array(self.stack[...i]); self.stack[i] = screen
          } else { self.stack.append(screen) }
        case .replace:
          self.turn += 1
          self.stack[self.stack.count - 1] = screen
        case .refresh:
          self.stack[self.stack.count - 1] = screen
        }
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
