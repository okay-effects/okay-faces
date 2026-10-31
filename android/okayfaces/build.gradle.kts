// THE FACE: a screen of an application's Scala drawn by Compose, its phone answered by Android
plugins {
  id("com.android.library")
  id("maven-publish")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "okay.faces.android"
  compileSdk = 35
  defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
  // the screens' Scala, every phone's (okay-faces-mobile): with it okay-faces', okay-simple's and Scala's own
  api("io.github.okay-effects:okay-faces-mobile_3:${property("okayFaces")}")
  val compose = platform("androidx.compose:compose-bom:2025.04.00")
  api(compose)
  api("androidx.compose.material3:material3")
  api("androidx.compose.ui:ui")
  api("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.compose.material:material-icons-core")
}

/** THE FACE AS A RELEASE: io.github.okay-effects:okay-faces-android, an AAR with its POM, written into build/repo —
 * `android/publish.sh` puts it onto okay-faces' GitHub Pages beside the Scala modules */
publishing {
  publications {
    register<MavenPublication>("release") {
      groupId = "io.github.okay-effects"
      artifactId = "okay-faces-android"
      version = project.property("version").toString()
      afterEvaluate { from(components["release"]) }
      pom {
        name.set("okay-faces-android")
        description.set("okay-faces on Android: an application's Scala drawn by Jetpack Compose, its phone answered by Android")
        url.set("https://github.com/okay-effects/okay-faces")
        licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
      }
    }
  }
  repositories { maven { name = "build"; url = uri(layout.buildDirectory.dir("repo")) } }
}
