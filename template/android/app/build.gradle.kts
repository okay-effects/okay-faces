plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "app"
  compileSdk = 35
  defaultConfig { applicationId = "com.example.app"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  packaging { resources { excludes += setOf("META-INF/*", "**/*.tasty", "rootdoc.txt", "library.properties") } }
}
dependencies {
  implementation("io.github.okay-effects:okay-faces-android:0.1.0")
  implementation("io.github.okay-effects:okay-effects-machine_3:0.1.1")
  // the app's own Scala: android/build.sh
  implementation(fileTree("../libs") { include("*.jar") })
}
