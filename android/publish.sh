#!/bin/sh
# THE ANDROID FACE AS A RELEASE: `android/publish.sh <version>` — io.github.okay-effects:okay-faces-android:<version>,
# built on okay-faces-mobile of the same version (from Pages), written into android/okayfaces/build/repo; the release
# copies it under maven/ on gh-pages beside the Scala modules (RELEASING.md).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
v=${1:?a version, the okay-faces release it is built on}
cd "$here"
rm -rf okayfaces/build/repo
gradle -q -PokayFaces="$v" -Pversion="$v" :okayfaces:publishReleasePublicationToBuildRepository
find okayfaces/build/repo -name "*.aar" -o -name "*.pom" | sed "s#$here/##"
