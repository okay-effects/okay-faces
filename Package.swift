// swift-tools-version:5.9
// okay-faces' iOS face, its Swift half (specs/mobile.md): a SwiftUI face that knows no application — it draws the
// screens an application's Scala Native library gives and sends back the choices. The library is the application's,
// linked by the app (its XCFramework and `-lc++`); here only its C header. At the repository's root, so Xcode adds it
// by the repository's address and a release's tag; its sources are ios/swift.
import PackageDescription

let package = Package(
  name: "OkayFaces",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [.library(name: "OkayFaces", targets: ["OkayFaces"])],
  targets: [
    .systemLibrary(name: "COkayFaces", path: "ios/swift/Sources/COkayFaces"),
    .target(name: "OkayFaces", dependencies: ["COkayFaces"], path: "ios/swift/Sources/OkayFaces"),
  ],
  swiftLanguageVersions: [.v5]
)
