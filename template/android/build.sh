#!/bin/sh
# THE APP'S SCALA FOR ANDROID: its own classes jarred into android/libs; okay-faces and Scala come from Maven.
# Then `gradle -p android :app:assembleDebug` (a JDK 17 or 21 for Gradle).
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here"
sbt -batch -error app/compile
rm -rf android/libs && mkdir -p android/libs
(cd app/.jvm/target/scala-*/classes && jar cf "$here/android/libs/app.jar" .)
echo android/libs/app.jar
