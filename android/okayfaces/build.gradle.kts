// THE FACE: a screen of an application's Scala drawn by Compose, its phone answered by Android
plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "okay.faces.android"
  compileSdk = 35
  defaultConfig { minSdk = 26 }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
}
dependencies {
  // the application's Scala, and okay-faces' and Scala's own: put into ../libs by android/build.sh
  api(fileTree("../libs") { include("*.jar") })
  val compose = platform("androidx.compose:compose-bom:2025.04.00")
  api(compose)
  api("androidx.compose.material3:material3")
  api("androidx.compose.ui:ui")
  api("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.compose.material:material-icons-core")
}
