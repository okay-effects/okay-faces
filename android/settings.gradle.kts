// okay-faces on Android (specs/mobile.md, "Android"): the program is Scala, JVM bytecode on Android's runtime; the
// screen is Jetpack Compose, the library `okayfaces`, knowing no application; `shell` runs the examples
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositories { google(); mavenCentral() }
}
rootProject.name = "okay-faces-android"
include(":okayfaces", ":shell")
