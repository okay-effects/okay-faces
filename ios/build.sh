#!/bin/sh
# AN APPLICATION'S LIBRARY FOR iOS (specs/mobile.md): `ios/build.sh [sbt project] [name] [out dir]` — Scala Native
# linked twice, for the simulator and for a device (arm64 both), and the two put into one XCFramework, <out>/<name>
# .xcframework: Xcode takes the slice the build is for, so an app links the one framework and needs no search path.
# Defaults: the examples (mobileExampleNative), OkayExample, ios/example/Shell/lib.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
project=${1:-mobileExampleNative}
name=${2:-OkayExample}
out=${3:-$here/ios/example/Shell/lib}
cd "$here"
work=$(mktemp -d)
for target in simulator device; do
  # a target's objects are its own: Scala Native would take the other target's from its last link, and `ar` would
  # add to the archive the last link left
  find mobile -path "*/.native/target/*/native" -type d -prune -exec rm -rf {} +
  find mobile -path "*/.native/target/*" -name "lib*.a" -delete
  OKAY_PLATFORMS=native OKAY_IOS_TARGET=$target sbt -batch -error "$project/nativeLink" | grep -v emit-static-lib || true
  lib=$(ls -t $(find mobile -path "*/.native/target/*" -name "lib*.a") | head -1)
  mkdir -p "$work/$target" && cp "$lib" "$work/$target/lib$name.a"
done
mkdir -p "$out" && rm -rf "$out/$name.xcframework"
xcodebuild -create-xcframework -library "$work/simulator/lib$name.a" -library "$work/device/lib$name.a" \
  -output "$out/$name.xcframework" >/dev/null
rm -rf "$work"
echo "$out/$name.xcframework"
