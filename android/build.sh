#!/bin/sh
# THE EXAMPLES' SCALA FOR ANDROID (specs/mobile.md): okay-faces' Scala (core, mobile) published to ~/.m2 as the
# snapshot okayfaces builds on, and the examples' own classes jarred into android/libs, which the shell takes; the
# rest comes from Maven. Then `gradle -p android :shell:assembleDebug`, or android/test.sh.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here"
dirs=$(sbt -batch -error "core/publishM2" "mobile/publishM2" "export mobileExample/Compile/fullClasspath" | tail -1)
rm -rf android/libs && mkdir -p android/libs
echo "$dirs" | tr ':' '\n' | grep "mobile/example" | while read -r d; do
  [ -d "$d" ] && (cd "$d" && jar cf "$here/android/libs/examples.jar" .)
done
ls android/libs
