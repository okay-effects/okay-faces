#!/bin/sh
# THE ANDROID FACE ON THE EMULATOR: the examples' Scala into libs, the shell built, its UI tests run on the device adb
# sees, the screens pulled into /tmp/okay-faces-android. A device (or `emulator -avd ...`) running first.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
"$here/build.sh"
cd "$here"
gradle -q :shell:connectedDebugAndroidTest
rm -rf /tmp/okay-faces-android && mkdir -p /tmp/okay-faces-android
adb pull /sdcard/Download/okay-faces/. /tmp/okay-faces-android/ >/dev/null
ls /tmp/okay-faces-android
