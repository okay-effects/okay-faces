// The whole of the app's Swift: its Scala opened by the class name of its `MobileApp`
import SwiftUI
import OkayFaces

@main
struct App: SwiftUI.App {
  var body: some Scene { WindowGroup { OkayFacesView(app: "app.CounterApp") } }
}
