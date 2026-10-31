// The whole of the app's Swift: its Scala opened by the class name of its `MobileApp`
import SwiftUI
import OkayFaces

@main
struct CounterApp: App {
  /// what only an app's delegate is told: the device token for notifications
  @UIApplicationDelegateAdaptor(OkayFacesAppDelegate.self) var delegate
  var body: some Scene { WindowGroup { OkayFacesView(app: "app.CounterApp") } }
}
