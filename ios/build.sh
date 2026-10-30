#!/bin/sh
# AN APPLICATION'S LIBRARY FOR iOS (specs/mobile.md): `ios/build.sh [simulator|device] [sbt project] [out dir]` —
# Scala Native linked into a static library for the target, copied as lib<name>.a into <out dir>/<target>/.
# Defaults: the simulator, the examples (mobileExampleNative), ios/example/Shell/lib.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
target=${1:-simulator}
project=${2:-mobileExampleNative}
out=${3:-$here/ios/example/Shell/lib}
cd "$here"
OKAY_PLATFORMS=native OKAY_IOS_TARGET=$target sbt -batch -error "$project/nativeLink" | grep -v emit-static-lib || true
lib=$(ls -t $(find mobile -path "*/.native/target/*" -name "lib*.a") | head -1)
[ -n "$lib" ] || { echo "no library linked"; exit 1; }
mkdir -p "$out/$target"
cp "$lib" "$out/$target/"
echo "$out/$target/$(basename "$lib")"
