#!/bin/sh
# THE APP'S SCALA FOR AN iPHONE: linked by Scala Native for the simulator and for a device, both into
# ios/lib/App.xcframework — Xcode takes the one its build is for.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here"
work=$(mktemp -d)
for target in simulator device; do
  # each target from a clean slate: the other's objects and archive gone
  rm -rf app/.native/target/*/native; find app/.native/target -name "lib*.a" -delete 2>/dev/null || true
  OKAY_IOS_TARGET=$target sbt -batch -error appNative/nativeLink | grep -v emit-static-lib || true
  mkdir -p "$work/$target" && cp "$(ls app/.native/target/*/lib*.a)" "$work/$target/libApp.a"
done
rm -rf ios/lib/App.xcframework && mkdir -p ios/lib
xcodebuild -create-xcframework -library "$work/simulator/libApp.a" -library "$work/device/libApp.a" \
  -output ios/lib/App.xcframework >/dev/null
rm -rf "$work"
echo ios/lib/App.xcframework
