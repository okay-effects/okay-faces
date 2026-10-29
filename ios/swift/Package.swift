// swift-tools-version:5.9
// okay-faces' iOS face, its Swift half (specs/ios.md): a SwiftUI face that knows no application — it draws the
// screens an application's Scala Native library gives and sends back the choices. The library is the application's,
// linked by the app (its static library and `-lc++`); here only its C header.
import PackageDescription

let package = Package(
  name: "OkayFaces",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [.library(name: "OkayFaces", targets: ["OkayFaces"])],
  targets: [
    .systemLibrary(name: "COkayFaces", path: "Sources/COkayFaces"),
    .target(name: "OkayFaces", dependencies: ["COkayFaces"], path: "Sources/OkayFaces"),
  ],
  swiftLanguageVersions: [.v5]
)
