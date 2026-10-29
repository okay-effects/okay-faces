// THE SHELL: the whole of the app's Swift — the application's library opened by its class name. Which one: the launch
// argument `-app <class>` (the examples' two), else the list.
import SwiftUI
import OkayFaces

@main
struct ShellApp: App {
  var body: some Scene {
    WindowGroup {
      OkayFacesView(app: UserDefaults.standard.string(forKey: "app") ?? "okay.faces.ios.example.TodoApp")
    }
  }
}
