// THE SHELL: the examples' app — `-e app <class>` (or the list) opened, the rest is okayfaces'
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
android {
  namespace = "okay.faces.android.shell"
  compileSdk = 35
  defaultConfig {
    applicationId = "io.github.okay_effects.faces.shell"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  packaging { resources { excludes += setOf("META-INF/*", "**/*.tasty", "rootdoc.txt", "library.properties") } }
}
dependencies {
  implementation(project(":okayfaces"))
  androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.00"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
