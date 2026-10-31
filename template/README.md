# A phone app on okay-faces

One Scala program — its screens and what it asks of the phone — drawn natively on an iPhone (SwiftUI) and on
Android (Jetpack Compose). Everything here comes from okay-faces' releases: the Scala from Maven on GitHub Pages,
the Swift package from GitHub, the Android face as an AAR. Copy this directory and start.

```
app/src/main/scala/app/Counter.scala   # the app: change it, both phones change
ios/                                   # the iPhone's shell: one Swift file, an XcodeGen project
android/                               # Android's shell: one Kotlin file, a Gradle project
```

## The app

```sh
sbt app/test                           # the app on the JVM, its phone in memory
```

A program of screens (`offer`, `show`, `confirm`, …, `import okay.faces._`) and of the phone (`save`, `load`,
`fetch`, `secret`, `remind`, `open`, `import okay.faces.mobile.phone._`), and a `MobileApp` the phones open by name.

## On an iPhone

Xcode and XcodeGen (`brew install xcodegen`):

```sh
ios/build.sh                           # the app's Scala: ios/lib/App.xcframework, the simulator and a device
cd ios && xcodegen && open Counter.xcodeproj
```

Run on a simulator; for your own iPhone choose your team under Signing & Capabilities (a free Apple ID will do),
connect the phone with Developer Mode on, run.

## On Android

The Android SDK, and Gradle on a JDK 17 or 21:

```sh
android/build.sh                       # the app's own classes into android/libs
cd android && echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
gradle :app:installDebug               # onto the emulator or the phone adb sees
```

## Versions

okay-faces 0.1.0 (`okay-faces-mobile`, `okay-faces-android`, the Swift package `OkayFaces`), okay-simple 0.1.1,
Scala 3.3, Scala Native 0.5. specs/mobile.md in okay-faces says how it works.
