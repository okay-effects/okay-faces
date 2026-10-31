// okay-faces on Android (specs/mobile.md, "Android"): the program is Scala, JVM bytecode on Android's runtime; the
// screen is Jetpack Compose, the library `okayfaces`, knowing no application; `shell` runs the examples
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositories {
    google(); mavenCentral()
    // okay-faces and okay-simple, from their releases on GitHub Pages; a snapshot of one's own from ~/.m2
    maven("https://okay-effects.github.io/okay-faces/maven")
    maven("https://okay-effects.github.io/okay-simple/maven")
    mavenLocal()
  }
}
rootProject.name = "okay-faces-android"
include(":okayfaces", ":shell")
