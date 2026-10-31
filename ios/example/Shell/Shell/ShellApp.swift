// THE SHELL: the whole of the app's Swift — the application's library opened by its class name. Which one: the launch
// argument `-app <class>` (the examples' two), else the list.
import SwiftUI
import OkayFaces

@main
struct ShellApp: App {
  /// what only an app's delegate is told: the device token for notifications
  @UIApplicationDelegateAdaptor(OkayFacesAppDelegate.self) var delegate
  init() {
    // `-reset YES`: what the app kept forgotten first — a test starts from nothing
    if UserDefaults.standard.bool(forKey: "reset") {
      for k in UserDefaults.standard.dictionaryRepresentation().keys where k.hasPrefix("okay-faces.") {
        UserDefaults.standard.removeObject(forKey: k)
      }
      // and the secrets — a session among them
      SecItemDelete([kSecClass as String: kSecClassGenericPassword,
                     kSecAttrService as String: "okay-faces"] as CFDictionary)
    }
  }
  var body: some Scene {
    WindowGroup {
      OkayFacesView(app: UserDefaults.standard.string(forKey: "app") ?? "okay.faces.mobile.example.TodoApp")
    }
  }
}
