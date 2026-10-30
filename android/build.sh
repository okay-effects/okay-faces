#!/bin/sh
# THE APPLICATION'S SCALA FOR ANDROID (specs/mobile.md): `android/build.sh [sbt project]` — the project's runtime
# classpath (its jar, okay-faces', okay-simple's, Scala's) copied into android/libs, which okayfaces takes; then
# `./gradlew`-less: `gradle -p android :shell:assembleDebug`. Default: the examples (mobileExample).
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
project=${1:-mobileExample}
cd "$here"
cp=$(sbt -batch -error "export $project/Runtime/fullClasspath" | tail -1)
rm -rf android/libs && mkdir -p android/libs
echo "$cp" | tr ':' '\n' | while read -r f; do
  case "$f" in
    *.jar) cp "$f" android/libs/ ;;
    */classes) [ -d "$f" ] && (cd "$f" && jar cf "$here/android/libs/classes-$(echo "$f" | md5 | cut -c1-8).jar" .) ;;
  esac
done
ls android/libs | wc -l
