// A PHONE APP ON okay-faces: one Scala program, an iPhone's (Scala Native, SwiftUI) and Android's (Scala on ART,
// Compose). `sbt app/test` checks it on the JVM; `ios/build.sh` and `android/build.sh` make each phone's app.
import sbtcrossproject.{CrossProject, CrossType}
import scala.scalanative.build._

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / resolvers ++= Seq("okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven")

val okayFaces = "0.1.0"
val okayEffects = "0.1.1"

/** THE APP: its screens and what it asks of the phone, written once; on the JVM (tests, Android) and Scala Native
 * (an iPhone, a static library for `ios/build.sh`) */
lazy val app = CrossProject("app", file("app"))(JVMPlatform, NativePlatform).crossType(CrossType.Pure)
  .settings(
    name := "app",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-faces-mobile" % okayFaces,
      "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects,
      "org.scalameta" %%% "munit" % "1.2.3" % Test))
  .jvmConfigure(_.withId("app")).nativeConfigure(_.withId("appNative").settings(Ios.library))
