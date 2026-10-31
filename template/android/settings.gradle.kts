// THE APP ON ANDROID: its Scala (android/build.sh puts it into libs/), the face from okay-faces' Maven on Pages
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
  repositories {
    google(); mavenCentral()
    maven("https://okay-effects.github.io/okay-faces/maven")
    maven("https://okay-effects.github.io/okay-simple/maven")
  }
}
rootProject.name = "app"
include(":app")
